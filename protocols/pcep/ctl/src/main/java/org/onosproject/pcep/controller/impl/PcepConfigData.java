/*
 * Copyright 2015-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.pcep.controller.impl;

import org.onlab.packet.IpAddress;
import org.onosproject.pcep.controller.PcepCfgData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides PCEP configuration data.
 */
public class PcepConfigData implements PcepCfgData {

    protected static final Logger log = LoggerFactory.getLogger(PcepConfigData.class);

    private int asNumber;
    private IpAddress ipAddress = null;

    /*
     * Constructor to initialize the values.
     */
    public PcepConfigData(IpAddress ipAddress, int asNumber) {
        this.asNumber = asNumber;
        this.ipAddress = ipAddress;
    }

    @Override
    public int asNumber() {
        return this.asNumber;
    }

    @Override
    public IpAddress ipAddress() {
        if (this.ipAddress != null) {
            return this.ipAddress;
        } else {
            return null;
        }
    }

    @Override
    public void setAsNumber(int asNumber) {
        this.asNumber = asNumber;
    }

    @Override
    public void setIpAddress(IpAddress ipAddress) {
        this.ipAddress = ipAddress;
    }
}
