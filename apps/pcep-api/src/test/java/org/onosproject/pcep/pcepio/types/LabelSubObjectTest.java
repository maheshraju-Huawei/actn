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
package org.onosproject.pcep.pcepio.types;

import com.google.common.testing.EqualsTester;
import org.junit.Test;

/**
 * Test of the LabelSubObject.
 */
public class LabelSubObjectTest {

    private final LabelSubObject subObj1 = LabelSubObject.of((byte) 0, (byte) 1, 20);
    private final LabelSubObject sameAsSubObj1 = LabelSubObject.of((byte) 0, (byte) 1, 20);
    private final LabelSubObject subObj2 = LabelSubObject.of((byte) 0, (byte) 1, 30);

    @Test
    public void basics() {
        new EqualsTester()
        .addEqualityGroup(subObj1, sameAsSubObj1)
        .addEqualityGroup(subObj2)
        .testEquals();
    }
}
