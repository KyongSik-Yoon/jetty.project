//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.extensions;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.zip.Deflater;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.common.extensions.compress.CompressExtension;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public class WebSocketExtensionFactory extends ContainerLifeCycle implements ExtensionFactory
{
    private WebSocketContainerScope container;
    private ServiceLoader<Extension> extensionLoader = ServiceLoader.load(Extension.class);
    private Map<String, Class<? extends Extension>> availableExtensions;
    private final InflaterPool inflaterPool = new InflaterPool(CompressionPool.INFINITE_CAPACITY, true);
    private final DeflaterPool deflaterPool = new DeflaterPool(CompressionPool.INFINITE_CAPACITY, Deflater.DEFAULT_COMPRESSION, true);

    public WebSocketExtensionFactory(WebSocketContainerScope container)
    {
        availableExtensions = new HashMap<>();
        for (Extension ext : extensionLoader)
        {
            if (ext != null)
                availableExtensions.put(ext.getName(),ext.getClass());
        }

        this.container = container;
        addBean(inflaterPool);
        addBean(deflaterPool);
    }

    @Override
    public Map<String, Class<? extends Extension>> getAvailableExtensions()
    {
        return availableExtensions;
    }

    @Override
    public Class<? extends Extension> getExtension(String name)
    {
        return availableExtensions.get(name);
    }

    @Override
    public Set<String> getExtensionNames()
    {
        return availableExtensions.keySet();
    }

    @Override
    public boolean isAvailable(String name)
    {
        return availableExtensions.containsKey(name);
    }

    @Override
    public Extension newInstance(ExtensionConfig config)
    {
        if (config == null)
        {
            return null;
        }

        String name = config.getName();
        if (StringUtil.isBlank(name))
        {
            return null;
        }

        Class<? extends Extension> extClass = getExtension(name);
        if (extClass == null)
        {
            return null;
        }

        try
        {
            Extension ext = container.getObjectFactory().createInstance(extClass);
            if (ext instanceof AbstractExtension)
            {
                AbstractExtension aext = (AbstractExtension)ext;
                aext.init(container);
                aext.setConfig(config);
            }
            if (ext instanceof CompressExtension)
            {
                CompressExtension cext = (CompressExtension)ext;
                cext.setInflaterPool(inflaterPool);
                cext.setDeflaterPool(deflaterPool);
            }

            return ext;
        }
        catch (Exception e)
        {
            throw new WebSocketException("Cannot instantiate extension: " + extClass, e);
        }
    }

    @Override
    public void register(String name, Class<? extends Extension> extension)
    {
        availableExtensions.put(name,extension);
    }

    @Override
    public void unregister(String name)
    {
        availableExtensions.remove(name);
    }

    @Override
    public Iterator<Class<? extends Extension>> iterator()
    {
        return availableExtensions.values().iterator();
    }
}
