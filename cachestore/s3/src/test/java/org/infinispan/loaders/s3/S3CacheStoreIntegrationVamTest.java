/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.loaders.s3;

import org.infinispan.commands.RemoteCommandFactory;
import org.infinispan.marshall.Marshaller;
import org.infinispan.marshall.VersionAwareMarshaller;
import org.testng.annotations.Test;

/**
 * S3CacheStoreIntegrationTest using production level marshaller.
 * 
 * @author Galder Zamarreño
 * @since 4.0
 */
@Test(groups = "unit", sequential = true, testName = "loaders.s3.S3CacheStoreIntegrationVamTest")
public class S3CacheStoreIntegrationVamTest extends S3CacheStoreIntegrationTest {
   @Override
   protected Marshaller getMarshaller() {
      VersionAwareMarshaller marshaller = new VersionAwareMarshaller();
      marshaller.init(Thread.currentThread().getContextClassLoader(), new RemoteCommandFactory());
      return marshaller;
   }
}
