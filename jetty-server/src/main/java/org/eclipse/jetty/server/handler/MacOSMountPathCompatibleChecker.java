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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.server.handler.ContextHandler.AliasCheck;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MacOS 10.15 have different real path with before version.
 * So content initializing failed due to the failed loading the resources has valid alias.
 *
 * Under 10.15
 * ---
 * Filesystem      Mounted On
 * /dev/disk1s1    /
 *
 * From 10.15
 * Filesystem      Mounted On
 * /dev/disk1s1   /System/Volumes/Data
 */

public class MacOSMountPathCompatibleChecker implements AliasCheck
{
    private static final Logger LOG = Log.getLogger(MacOSMountPathCompatibleChecker.class);

    static final boolean RUNNING_ON_MAC_OS = System.getProperty("os.name").startsWith("Mac OS X");

    @Override
    public boolean check(String uri, Resource resource)
    {
        if (!RUNNING_ON_MAC_OS)
        {
            return false;
        }

        if (!(resource instanceof PathResource))
        {
            return false;
        }

        PathResource pathResource = (PathResource) resource;
        Path path = pathResource.getPath();
        Path alias = pathResource.getAliasPath();

        try
        {
            boolean valid = isValidAlias(alias, Files.isSameFile(path, alias));

            if (valid && LOG.isDebugEnabled())
            {
                LOG.debug("Allow path by MacOS mount location {} --> {}", resource, pathResource.getAliasPath());
            }

            return valid;
        }
        catch (IOException e)
        {
            LOG.ignore(e);
            return false;
        }
    }

    boolean isValidAlias(Path alias, boolean isSamePathAndAlias)
    {
        return containsMacOSMountPath(alias) && isSamePathAndAlias;
    }

    private boolean containsMacOSMountPath(Path alias)
    {
        return alias.startsWith("/System/Volumes/Data");
    }
}
