/*
 * Copyright (c) 2014, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.hk2.tests.locator.messaging.basic;

import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.messaging.MessageReceiver;
import org.glassfish.hk2.api.messaging.SubscribeTo;
import org.jvnet.hk2.annotations.Service;

/**
 * @author jwells
 *
 */
@Service @PerLookup @MessageReceiver
public class PerLookupSubscriber {
    private Foo lastEvent;
    
    @SuppressWarnings("unused")
    private synchronized void subscribe(@SubscribeTo Foo event) {
        lastEvent = event;
    }
    
    public synchronized Foo getAndClearLastEvent() {
        Foo retVal = lastEvent;
        lastEvent = null;
        
        return retVal;
    }
    
    

}
