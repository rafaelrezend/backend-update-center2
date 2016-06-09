/*
 * Copyright (c) Robert Bosch GmbH. All rights reserved.
 */
package org.jvnet.hudson.update_center.wikiplugin;

import java.io.IOException;
import java.rmi.RemoteException;

import javax.xml.rpc.ServiceException;

import jenkins.plugins.confluence.soap.v1.RemotePage;

import org.jvnet.hudson.update_center.WikiPage;

/**
 * @author rar6si
 *
 */
public abstract class WikiPluginList {
    
    public abstract void initialize() throws IOException, ServiceException;

    public abstract String[] getLabels(RemotePage page) throws RemoteException;

    public abstract WikiPage getPage(String url) throws IOException, RemoteException;

}
