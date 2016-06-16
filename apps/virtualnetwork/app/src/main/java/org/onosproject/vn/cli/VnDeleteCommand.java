/*
 * Copyright 2016-present Open Networking Laboratory
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
package org.onosproject.vn.cli;

import org.apache.karaf.shell.commands.Argument;
import org.apache.karaf.shell.commands.Command;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.vn.manager.api.VnService;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Supports deleting virtual network.
 */
@Command(scope = "onos", name = "vn-delete", description = "Supports deleting virtual network.")
public class VnDeleteCommand extends AbstractShellCommand {
    private final Logger log = getLogger(getClass());

    @Argument(index = 0, name = "vnName", description = "Virtual network.", required = true, multiValued = false)
    String vnName = null;

    @Override
    protected void execute() {
        log.info("executing vn-delete");

        VnService service = get(VnService.class);

        if (!service.deleteVn(vnName)) {
            error("Virtual network deletion failed.");
            return;
        }
    }
}
