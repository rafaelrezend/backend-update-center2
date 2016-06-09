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

import java.io.IOException;
import java.net.URL;

import javax.xml.rpc.ServiceException;

import jenkins.plugins.confluence.soap.v1.ConfluenceSoapService;
import jenkins.plugins.confluence.soap.v1.ConfluenceSoapServiceServiceLocator;
import jenkins.plugins.confluence.soap.v1.RemotePage;
import jenkins.plugins.confluence.soap.v1.RemotePageSummary;

import org.apache.axis.utils.StringUtils;

/**
 * List of plugins from confluence. Primarily serve as a cache.
 *
 * <p>
 * See http://confluence.atlassian.com/display/DOC/Remote+API+Specification for the confluence API.
 *
 * @author Kohsuke Kawaguchi
 */
public class JenkinsConfluenceV1PluginList extends JenkinsConfluencePluginList {

    public JenkinsConfluenceV1PluginList() throws IOException, ServiceException {
        super(null, null, null, null, null);
    }

    public JenkinsConfluenceV1PluginList(String wikiBaseUrl, String wikiSpaceName, String wikiPageTitle, String wikiUser, String wikiPassword) throws IOException,
            ServiceException {
	
	super(wikiBaseUrl, wikiSpaceName, wikiPageTitle, wikiUser, wikiPassword);
    }

    public JenkinsConfluenceV1PluginList(ConfluenceSoapService service) throws IOException, ServiceException {
	super(null, null, null, null, null);
        this.service = service;
    }

    @Override
    public void initialize() throws IOException, ServiceException {
        if (!StringUtils.isEmpty(wikiBaseUrl)) {
            service = connectV1(new URL(wikiBaseUrl));
        } else {
            service = connectV1(new URL(WIKI_URL));
        }
        String token = "";
        if (!StringUtils.isEmpty(wikiUser) && !StringUtils.isEmpty(wikiPassword)) {
            token = service.login(wikiUser, wikiPassword);
        }
        String wikiSpaceNameLocal = "JENKINS";
        String wikiPageTitleLocal = "Plugins";
        if (!StringUtils.isEmpty(wikiSpaceName)) {
            wikiSpaceNameLocal = wikiSpaceName;
        }
        if (!StringUtils.isEmpty(wikiPageTitle)) {
            wikiPageTitleLocal = wikiPageTitle;
        }

        RemotePage page = service.getPage(token, wikiSpaceNameLocal, wikiPageTitleLocal);

        for (RemotePageSummary child : service.getChildren("", page.getId())) {
            children.put(normalize(child.getTitle()), child);
        }
        normalizedTitles = children.keySet().toArray(new String[children.size()]);
    }

    /**
     * Connects to the confluence server.
     */
    @Override
    public ConfluenceSoapService connectV1(URL confluenceUrl) throws ServiceException, IOException {
        ConfluenceSoapServiceServiceLocator loc = new ConfluenceSoapServiceServiceLocator();
        return loc.getConfluenceserviceV1(new URL(confluenceUrl, "rpc/soap-axis/confluenceservice-v1"));
    }
}
