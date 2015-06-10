/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Sun Microsystems, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jvnet.hudson.update_center;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentFactory;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.sonatype.nexus.index.ArtifactInfo;

import java.io.File;
import java.io.IOException;
import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An entry of a plugin in the update center metadata.
 *
 * @author Kohsuke Kawaguchi
 */
public class PluginV1 {
    /**
     * Plugin artifact ID.
     */
    public final String artifactId;
    /**
     * Latest version of this plugin.
     */
    public final HPI latest;
    /**
     * Previous version of this plugin.
     */
    public final HPI previous;
    /**
     * Confluence page of this plugin in Wiki.
     * Null if we couldn't find it.
     */
    public final WikiV1Page page;

    /**
     * Confluence labels for the plugin wiki page.
     * Null if wiki page wasn't found.
     */
    private String[] labels;
    private boolean labelsRead = false;

    /**
     * Deprecated plugins should not be included in update center.
     */
    private boolean deprecated = false;

    private final SAXReader xmlReader;

    /**
     * POM parsed as a DOM.
     */
    private final Document pom;

    public PluginV1(String artifactId, HPI latest, HPI previous, ConfluenceV1PluginList cpl) throws IOException {
        this.artifactId = artifactId;
        this.latest = latest;
        this.previous = previous;
        this.xmlReader = createXmlReader();
        this.pom = readPOM();
        this.page = findPage(cpl);
    }

    public PluginV1(PluginHistory hpi, ConfluenceV1PluginList cpl) throws IOException {
        this.artifactId = hpi.artifactId;
        List<HPI> versions = new ArrayList<HPI>();
        for (HPI h : hpi.artifacts.values()) {
            try {
                h.getManifest();
                versions.add(h);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to resolve "+h+". Dropping this version.",e);
            }
        }
        
        this.latest = versions.get(0);
        this.previous = versions.size()>1 ? versions.get(1) : null;

        // Doublecheck that latest-by-version is also latest-by-date:
        checkLatestDate(versions, latest);

        this.xmlReader = createXmlReader();
        this.pom = readPOM();
        this.page = findPage(cpl);
    }

    public PluginV1(HPI hpi, ConfluenceV1PluginList cpl) throws IOException {
        this(hpi.artifact.artifactId, hpi,  null, cpl);
    }

    private SAXReader createXmlReader() {
        DocumentFactory factory = new DocumentFactory();
        factory.setXPathNamespaceURIs(
                Collections.singletonMap("m", "http://maven.apache.org/POM/4.0.0"));
        return new SAXReader(factory);
    }

    private void checkLatestDate(Collection<HPI> artifacts, HPI latestByVersion) throws IOException {
        TreeMap<Long,HPI> artifactsByDate = new TreeMap<Long,HPI>();
        for (HPI h : artifacts)
            artifactsByDate.put(h.getTimestamp(), h);
        HPI latestByDate = artifactsByDate.get(artifactsByDate.lastKey());
        if (latestByDate != latestByVersion)
            System.out.println(
                "** Latest-by-version (" + latestByVersion.version + ','
                + latestByVersion.getTimestampAsString() + ") doesn't match latest-by-date ("
                + latestByDate.version + ',' + latestByDate.getTimestampAsString() + ')');
    }

    private Document readPOM() throws IOException {
        try {
            File pomFile = latest.resolvePOM();
            return pomFile != null?xmlReader.read(pomFile):null;
        } catch (DocumentException e) {
            System.err.println("** Can't parse POM for "+artifactId);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Locates the page for this plugin on Wiki.
     *
     * <p>
     * First we'll try to parse POM and obtain the URL.
     * If that fails, find the nearest name from the children list.
     */
    private WikiV1Page findPage(ConfluenceV1PluginList cpl) throws IOException {
        try {
            String p = OVERRIDES.getProperty(artifactId);
            if(p!=null)
                return cpl.getPage(p);
        } catch (RemoteException e) {
            System.err.println("** Override failed for "+artifactId);
            e.printStackTrace();
        }

        if (pom != null) {
            String wikiPage = selectSingleValue(pom, "/project/url");
            if (wikiPage != null) {
                try {
                    return cpl.getPage(wikiPage); // found the confluence page successfully
                } catch (RemoteException e) {
                    System.err.println("** Failed to fetch "+wikiPage);
                    e.printStackTrace();
                } catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                }
            }
        }

        // try to guess the Wiki page
        try {
            return cpl.findNearest(artifactId);
        } catch (RemoteException e) {
            System.err.println("** Failed to locate nearest");
            e.printStackTrace();
        }

        return null;
    }

    private static Node selectSingleNode(Document pom, String path) {
        Node result = pom.selectSingleNode(path);
        if (result == null)
            result = pom.selectSingleNode(path.replaceAll("/", "/m:"));
        return result;
    }

    private static String selectSingleValue(Document dom, String path) {
        Node node = selectSingleNode(dom, path);
        return node != null ? ((Element)node).getTextTrim() : null;
    }

    private static final Pattern HOSTNAME_PATTERN =
        Pattern.compile("(?:://|scm:git:(?!\\w+://))(?:\\w*@)?([\\w.-]+)[/:]");

    /**
     * Get hostname of SCM specified in POM of latest release, or null.
     * Used to determine if source lives in github or svn.
     */
    public String getScmHost() {
        if (pom != null) {
            String scm = selectSingleValue(pom, "/project/scm/connection");
            if (scm == null) {
                // Try parent pom
                Element parent = (Element)selectSingleNode(pom, "/project/parent");
                if (parent != null) try {
                    Document parentPom = xmlReader.read(
                            latest.repository.resolve(
                                    new ArtifactInfo("",
                                            parent.element("groupId").getTextTrim(),
                                            parent.element("artifactId").getTextTrim(),
                                            parent.element("version").getTextTrim(),
                                            ""), "pom", null));
                    scm = selectSingleValue(parentPom, "/project/scm/connection");
                } catch (Exception ex) {
                    System.out.println("** Failed to read parent pom");
                    ex.printStackTrace();
                }
            }
            if (scm != null) {
                Matcher m = HOSTNAME_PATTERN.matcher(scm);
                if (m.find())
                    return m.group(1);
                else System.out.println("** Unable to parse scm/connection: " + scm);
            }
            else System.out.println("** No scm/connection found in pom");
        }
        return null;
    }

    public String[] getLabels() {
        if (!labelsRead) readLabels();
        return labels;
    }

    public boolean isDeprecated() {
        if (!labelsRead) readLabels();
        return deprecated;
    }

    private void readLabels() {
        if (page!=null)
            labels = page.getLabels();

        if (labels != null)
            for (String label : labels)
                if ("deprecated".equals(label)) {
                    deprecated = true;
                    break;
                }
        this.labelsRead = true;
    }

    /**
     * Obtains the excerpt of this wiki page in HTML. Otherwise null.
     */
    public String getExcerptInHTML() {
        String content = page.getContent();
        if(content==null)
            return null;

        Matcher m = EXCERPT_PATTERN.matcher(content);
        if(!m.find())
            return null;

        String excerpt = m.group(1);
        String oneLiner = NEWLINE_PATTERN.matcher(excerpt).replaceAll(" ");
        excerpt = HYPERLINK_PATTERN.matcher(oneLiner).replaceAll("<a href='$2'>$1</a>");
        if (latest.isAlphaOrBeta())
            excerpt = "<b>(This version is experimental and may change in backward incompatible way</b> <br><br>"+excerpt;
        return excerpt;
    }

    // Tweaking to ignore leading whitespace after the initial {excerpt}
    private static final Pattern EXCERPT_PATTERN = Pattern.compile("\\{excerpt(?::hidden(?:=true)?)?\\}\\s*(.+)\\{excerpt\\}", Pattern.DOTALL);
    private static final Pattern HYPERLINK_PATTERN = Pattern.compile("\\[([^|\\]]+)\\|([^|\\]]+)(|([^]])+)?\\]");
    private static final Pattern NEWLINE_PATTERN = Pattern.compile("(?:\\r\\n|\\n)");

    public String getTitle() {
        String title = page != null ? page.getTitle() : null;
        if (title == null)
            title = selectSingleValue(pom, "/project/name");
        if (title == null)
            title = artifactId;
        return title;
    }

    public String getWiki() {
        String wiki = "";
        if (page!=null) {
            wiki = page.getUrl();
        }
        return wiki;
    }
    
    public JSONObject toJSON() throws IOException {
        JSONObject json = latest.toJSON(artifactId);

        SimpleDateFormat fisheyeDateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.00Z'", Locale.US);
        fisheyeDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        json.put("releaseTimestamp", fisheyeDateFormatter.format(latest.getTimestamp()));
        if (previous!=null) {
            json.put("previousVersion", previous.version);
            json.put("previousTimestamp", fisheyeDateFormatter.format(previous.getTimestamp()));
        }

        json.put("title", getTitle());
        if (page!=null) {
            json.put("wiki",page.getUrl());
            String excerpt = getExcerptInHTML();
            // TODO also had problems with Emma and Emma Code Coverage excerpts; was
            // "excerpt": "Allows you to add a column that displays line coverage percentages based on EMMA. {info}This functionality is included and superseeded by the \uFEFF[JENKINS:JaCoCo Plugin] now\\!{info}",
            // according to one source but the other lacked the anomalous BOM.
            if (excerpt!=null && !excerpt.startsWith("{"))
                json.put("excerpt",excerpt);
            String[] labelList = getLabels();
            if (labelList!=null)
                json.put("labels",labelList);
        }
        String scm = getScmHost();
        if (scm!=null) {
            json.put("scm", scm);
        }

        if (!json.has("excerpt")) {
            // fall back to <description>, which is plain text but still better than nothing.
            if(pom != null)
            {
                String description = plainText2html(selectSingleValue(pom, "/project/description"));
                if (description!=null)
                    json.put("excerpt",description);
            }
        }

        HPI hpi = latest;
        json.put("requiredCore", hpi.getRequiredJenkinsVersion());

        if (hpi.getCompatibleSinceVersion() != null) {
            json.put("compatibleSinceVersion",hpi.getCompatibleSinceVersion());
        }
        if (hpi.getSandboxStatus() != null) {
            json.put("sandboxStatus",hpi.getSandboxStatus());
        }

        JSONArray deps = new JSONArray();
        for (HPI.Dependency d : hpi.getDependencies())
            deps.add(d.toJSON());
        json.put("dependencies",deps);

        JSONArray devs = new JSONArray();
        List<HPI.Developer> devList = hpi.getDevelopers();
        if (!devList.isEmpty()) {
            for (HPI.Developer dev : devList)
                devs.add(dev.toJSON());
        } else {
            String builtBy = latest.getBuiltBy();
            if (builtBy!=null)
                devs.add(new HPI.Developer("", builtBy, "").toJSON());
        }
        json.put("developers", devs);
        json.put("gav", hpi.getGavId());

        return json;
    }

    private String plainText2html(String plainText) {
        if (plainText == null || plainText.length() == 0) {
            return "";
        }
        return plainText.replace("&","&amp;").replace("<","&lt;");
    }

    private static final Properties OVERRIDES = new Properties();

    static {
        try {
            OVERRIDES.load(PluginV1.class.getClassLoader().getResourceAsStream("wiki-overrides.properties"));
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(PluginV1.class.getName());
}