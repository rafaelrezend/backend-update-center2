/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc.
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
package org.jvnet.hudson.update_center.wikiplugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.rpc.ServiceException;

import jenkins.plugins.confluence.soap.v1.ConfluenceSoapService;
import jenkins.plugins.confluence.soap.v1.RemoteLabel;
import jenkins.plugins.confluence.soap.v1.RemotePage;
import jenkins.plugins.confluence.soap.v1.RemotePageSummary;

import org.apache.axis.utils.StringUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.jvnet.hudson.update_center.WikiPage;

/**
 * List of plugins from confluence. Primarily serve as a cache.
 *
 * <p>
 * See http://confluence.atlassian.com/display/DOC/Remote+API+Specification for the confluence API.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class JenkinsConfluencePluginList extends WikiPluginList {
    
    protected ConfluenceSoapService service;

    protected final Map<String, RemotePageSummary> children = new HashMap<String, RemotePageSummary>();

    protected String[] normalizedTitles;

    protected String wikiSessionId;

    protected final String WIKI_URL = "https://wiki.jenkins-ci.org/";

    protected final String wikiBaseUrl;

    protected final String wikiSpaceName;

    protected final String wikiPageTitle;

    protected final String wikiUser;

    protected final String wikiPassword;

    protected final Map<Long, String[]> labelCache = new HashMap<Long, String[]>();

    protected final File cacheDir = new File(System.getProperty("user.home"), ".wiki.jenkins-cache");

    public JenkinsConfluencePluginList() throws IOException, ServiceException {
        this(null, null, null, null, null);
    }

    public JenkinsConfluencePluginList(String wikiBaseUrl, String wikiSpaceName, String wikiPageTitle, String wikiUser, String wikiPassword) throws IOException,
            ServiceException {
        if (wikiBaseUrl == null) {
            this.wikiBaseUrl = WIKI_URL;
        } else {
            this.wikiBaseUrl = wikiBaseUrl;
        }
        this.wikiSpaceName = wikiSpaceName;
        this.wikiPageTitle = wikiPageTitle;
        this.wikiUser = wikiUser;
        this.wikiPassword = wikiPassword;

        cacheDir.mkdirs();
    }

    public JenkinsConfluencePluginList(ConfluenceSoapService service) throws IOException, ServiceException {
        this.service = service;

        wikiBaseUrl = WIKI_URL;
        wikiSpaceName = null;
        wikiPageTitle = null;
        wikiUser = null;
        wikiPassword = null;

        cacheDir.mkdirs();

    }

    public abstract void initialize() throws IOException, ServiceException;

    /**
     * Connects to the confluence server.
     */
    public abstract ConfluenceSoapService connectV1(URL confluenceUrl) throws ServiceException, IOException;

    protected void checkInitialized() {
        if (service == null) {
            throw new IllegalStateException("Variable 'service' is not initialized. Call 'initialize()' first.");
        }
        if (normalizedTitles == null) {
            throw new IllegalStateException("Variable 'normalizedTitles' is not initialized. Call 'initialize()' first.");
        }
    }

    /**
     * Make the page title as close to artifactId as possible.
     */
    protected final String normalize(String title) {
        title = title.toLowerCase().trim();
        if (title.endsWith("plugin")) {
            title = title.substring(0, title.length() - 6).trim();
        }
        return title.replace(" ", "-");
    }

    /**
     * @param url
     *            Wiki URL, in the canonical URL format.
     * @return The identifier we need to fetch the given URL via the Confluence API.
     */
    protected static String getIdentifierForUrl(String url) {
        URI pageUri = URI.create(url);
        String path = pageUri.getPath();
        if (path.equals("/pages/viewpage.action")) {
            // This is the canonical URL format for titles with odd characters, e.g. "Anything Goes" Formatter Plugin
            return pageUri.getQuery().replace("pageId=", "");
        }

        // In all other cases, we can just take the title straight from the URL
        return path.replaceAll("(?i)/display/JENKINS/", "").replace("+", " ");
    }

    public final WikiPage getPage(String pomUrl) throws IOException {
        checkInitialized();

        String url = resolveWikiUrl(pomUrl);
        if (url == null) {
            return null;
        }

        // Determine the page identifier for the given wiki URL
        String cacheKey = getIdentifierForUrl(url);

        // Load the serialised page from the cache, if we retrieved it within the last day
        File cache = new File(cacheDir, cacheKey + ".page");
        if (cache.exists() && cache.lastModified() >= System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)) {
            try {
                FileInputStream f = new FileInputStream(cache);
                try {
                    Object o = new ObjectInputStream(f).readObject();
                    if (o instanceof WikiPage) {
                        return (WikiPage) o;
                    }

                    // Cache file (somehow) has the wrong type; fall through to retrieve the page
                    System.out.println("** Ignoring cached wiki data with unexpected type: " + o);
                } finally {
                    f.close();
                }
            } catch (Exception e) {
                // Fall through to retrieve the page if anything goes wrong with parsing the cache file
                System.out.println("** Failed to read cached wiki data: " + e);
            }
        }

        // Otherwise fetch it from the wiki and cache the page
        try {
            RemotePage page;
            if (NumberUtils.isDigits(cacheKey)) {
                System.out.println("=> Fetching wiki page by ID " + cacheKey);
                page = service.getPage("", Long.parseLong(cacheKey));
            } else {
                System.out.println("=> Fetching wiki page by name: " + cacheKey);
                page = service.getPage("", "JENKINS", cacheKey);
            }
            RemoteLabel[] labels = service.getLabelsById("", page.getId());
            WikiPage p = new WikiPage(page, labels);
            writeToCache(cache, p);
            return p;
        } catch (RemoteException e) {
            // Something went wrong; delete the cache file
            cache.delete();
            throw e;
        }

    }

    /**
     * Determines the full wiki URL for a given Confluence short URL.
     *
     * @param id
     *            Short URL ID.
     * @return The full wiki URL.
     * @throws IOException
     *             If accessing the wiki fails.
     */
    protected final String resolveLink(String id) throws IOException {
        File cache = new File(cacheDir, id + ".link");
        if (cache.exists()) {
            return FileUtils.readFileToString(cache);
        }

        String url;
        try {
            // Avoid creating lots of sessions on wiki server.. get a session and reuse it.
            if (wikiSessionId == null) {
                wikiSessionId = initSession(WIKI_URL);
            }
            url = checkRedirect(WIKI_URL + "pages/tinyurl.action?urlIdentifier=" + id, wikiSessionId);
            FileUtils.writeStringToFile(cache, url);
        } catch (IOException e) {
            throw new RemoteException("Failed to lookup tinylink redirect", e);
        }
        return url;
    }

    /**
     * Attempts to determine the canonical wiki URL for a given URL.
     *
     * @param url
     *            Any URL.
     * @return A canonical URL to a wiki page, or {@code null} if the URL is not a child of the "Plugins" wiki page.
     * @throws IOException
     *             If resolving a short URL fails.
     */
    public final String resolveWikiUrl(String url) throws IOException {
        // Empty or null values can't be good
        if (url == null || url.isEmpty()) {
            System.out.println("** Wiki URL is missing");
            return null;
        }

        // If the URL is a short URL (e.g. "/x/tgeIAg"), then resolve the target URL
        Matcher tinylink = TINYLINK_PATTERN.matcher(url);
        if (tinylink.matches()) {
            url = resolveLink(tinylink.group(1));
        }

        // Fix up URLs with old hostnames or paths
        for (String p : OLD_URL_PREFIXES) {
            if (url.startsWith(p)) {
                url = url.replace(p, WIKI_URL).replaceAll("(?i)/HUDSON/", "/JENKINS/");
            }
        }

        // Reject the URL if it's not on the wiki at all
        if (!url.startsWith(WIKI_URL)) {
            System.out.println("** Wiki URLs should start with " + WIKI_URL);
            return null;
        }

        // Strip trailing slashes (e.g.
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        return url;
    }

    /**
     * Writes an object to a cache file.
     *
     * In case another update center runs concurrently, write to a temporary file and then atomically rename it.
     */
    protected final void writeToCache(File cache, Object o) throws IOException {
        File tmp = new File(cache + ".tmp");
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(tmp));
        try {
            oos.writeObject(o);
        } finally {
            oos.close();
        }
        cache.delete();
        tmp.renameTo(cache);
        tmp.delete();
    }

    protected static String checkRedirect(String url, String sessionId) throws IOException {
        return connect(url, sessionId).getHeaderField("Location");
    }

    protected static String initSession(String url) throws IOException {
        String cookie = connect(url, null).getHeaderField("Set-Cookie");
        return cookie.substring(0, cookie.indexOf(';')); // Remove ;Path=/
    }

    protected static HttpURLConnection connect(String url, String sessionId) throws IOException {
        HttpURLConnection huc = (HttpURLConnection) new URL(url).openConnection();
        huc.setInstanceFollowRedirects(false);
        huc.setDoOutput(false);
        if (sessionId != null) {
            huc.addRequestProperty("Cookie", sessionId);
        }
        InputStream i = huc.getInputStream();
        while (i.read() >= 0) {
            ;
        } // Drain stream
        return huc;
    }

    public final String[] getLabels(RemotePage page) throws RemoteException {
        checkInitialized();

        String token = "";
        if (!StringUtils.isEmpty(wikiUser) && !StringUtils.isEmpty(wikiPassword)) {
            token = service.login(wikiUser, wikiPassword);
        }

        String[] r = labelCache.get(page.getId());
        if (r == null) {
            RemoteLabel[] labels = service.getLabelsById(token, page.getId());
            if (labels == null) {
                return new String[0];
            }
            ArrayList<String> result = new ArrayList<String>(labels.length);
            for (RemoteLabel label : labels) {
                if (label.getName().startsWith("plugin-")) {
                    result.add(label.getName().substring(7));
                }
            }
            r = result.toArray(new String[result.size()]);
            labelCache.put(page.getId(), r);
        }
        return r;
    }

    protected static final String[] OLD_URL_PREFIXES = {
            "https://wiki.jenkins-ci.org/display/JENKINS/",
            "http://wiki.jenkins-ci.org/display/JENKINS/",
            "http://wiki.hudson-ci.org/display/HUDSON/",
            "http://hudson.gotdns.com/wiki/display/HUDSON/",
    };

    protected static final Pattern TINYLINK_PATTERN = Pattern.compile(".*/x/(\\w+)");
}
