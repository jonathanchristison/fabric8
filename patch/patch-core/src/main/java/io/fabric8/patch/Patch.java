/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.patch;

import java.util.Collection;
import java.util.Map;

public interface Patch {
    
    String getId();
    
    String getDescription();

    String getMigratorBundle();

    Collection<String> getBundles();

    /**
     * Get the collection of required patches. These patches need to be installed before installing this patch.
     */
    Collection<String> getRequirements();

    /**
     * Get the collection of plain files that will get patched.
     */
    Collection<String> getFiles();

    /**
     * Get the version range specified for updating the bundle or <code>null</code> if no version range is specified explicitly.
     */
    String getVersionRange(String bundle);

    boolean isInstalled();

    Result getResult();
    
    Result simulate();
    
    Result install();

    Result install(boolean force, boolean synchronous);

    void rollback(boolean force);

}
