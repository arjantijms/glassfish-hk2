/*
 * Copyright (c) 2015, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.hk2.runlevel.tests.listener1;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

/**
 * @author jwells
 *
 */
public abstract class AbstractLeveledService {
    @Inject
    private ServiceRegistry registry;
    
    public abstract int getLevel();
    
    @PostConstruct
    private void postConstruct() {
        registry.up(getLevel());
    }
    
    @PreDestroy
    private void preDestroy() {
        registry.down(getLevel());
    }

}
