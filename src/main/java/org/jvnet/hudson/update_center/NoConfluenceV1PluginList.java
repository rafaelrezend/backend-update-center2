/*
 * The MIT License
 *
 * Copyright (c) 2013 IKEDA Yasuyuki
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

import java.io.IOException;
import java.rmi.RemoteException;

import javax.xml.rpc.ServiceException;

import jenkins.plugins.confluence.soap.v1.RemotePage;

/**
 * Dummy class not to refer Confluence
 *
 */
public class NoConfluenceV1PluginList extends ConfluenceV1PluginList
{
    public NoConfluenceV1PluginList() throws IOException, ServiceException
    {
    }

    @Override
    public void initialize() throws IOException, ServiceException {
        // No-op
    }

    @Override
    public WikiV1Page findNearest(String pluginArtifactId)
            throws RemoteException
    {
        return null;
    }

    @Override
    public String[] getLabels(RemotePage page) throws RemoteException
    {
        // return new String[0];
        return null;
    }

    @Override
    public WikiV1Page getPage(String url) throws RemoteException
    {

        return null;
    }
}