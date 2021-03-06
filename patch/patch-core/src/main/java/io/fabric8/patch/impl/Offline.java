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
package io.fabric8.patch.impl;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.fabric8.common.util.IOHelpers;
import io.fabric8.patch.PatchException;
import org.apache.felix.utils.version.VersionRange;
import org.apache.felix.utils.version.VersionTable;
import org.osgi.framework.Version;

import static io.fabric8.patch.impl.Offline.Artifact.isSameButVersion;
import static io.fabric8.common.util.IOHelpers.close;
import static io.fabric8.common.util.IOHelpers.readLines;
import static io.fabric8.common.util.IOHelpers.writeLines;

public class Offline {

    private static final String PATCH_BACKUPS = "data/patch/backups";
    private static final String OVERRIDE_RANGE = ";range=";

    public static final int DEBUG = 0;
    public static final int INFO = 1;
    public static final int WARN = 2;
    public static final int ERROR = 3;

    private final File karafBase;
    private final Logger logger;

    public interface Logger {
        void log(int level, String message);
    }

    public static class SysLogger implements Logger {
        @Override
        public void log(int level, String message) {
            switch (level) {
                case Offline.DEBUG: System.out.println("DEBUG: " + message); break;
                case Offline.INFO:  System.out.println("INFO:  " + message); break;
                case Offline.WARN:  System.out.println("WARN:  " + message); break;
                case Offline.ERROR: System.out.println("ERROR: " + message); break;
            }
        }
    }

    public Offline(File karafBase) {
        this(karafBase, new SysLogger());
    }

    public Offline(File karafBase, Logger logger) {
        this.karafBase = karafBase;
        this.logger = logger;
    }

    public void apply(File patchZip) throws IOException {
        ZipFile zipFile = new ZipFile(patchZip);
        try {
            List<PatchData> patches = extractPatch(zipFile);
            if (patches.isEmpty()) {
                log(WARN, "No patch to apply");
            } else {
                for (PatchData data : patches) {
                    applyPatch(data, zipFile, null);
                }
            }
        } finally {
            close(zipFile);
        }
    }

    public void rollback(File patchZip) throws IOException {
        ZipFile zipFile = new ZipFile(patchZip);
        try {
            List<PatchData> patches = extractPatch(zipFile);
            if (patches.isEmpty()) {
                log(WARN, "No patch to apply");
            } else {
                for (PatchData data : patches) {
                    rollbackPatch(data);
                }
            }
        } finally {
            close(zipFile);
        }
    }

    public void rollbackPatch(PatchData patch) throws IOException {
        log(DEBUG, String.format("Rolling back patch %s / %s", patch.getId(), patch.getDescription()));
        for (String file : patch.getFiles()) {
            restore(patch, file);
        }
    }

    public void applyConfigChanges(PatchData patch, File storage) throws IOException {
        applyPatch(patch, null, storage);
    }

    protected List<PatchData> extractPatch(ZipFile zipFile) throws IOException {
        List<PatchData> patches = new ArrayList<PatchData>();
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                String entryName = entry.getName();
                if (entryName.endsWith(".patch") && !entryName.contains("/")) {
                    InputStream fis = zipFile.getInputStream(entry);
                    try {
                        PatchData patch = PatchData.load(fis);
                        patches.add(patch);
                    } finally {
                        close(fis);
                    }
                }
            }
        }
        return patches;
    }

    protected void applyPatch(PatchData patch, ZipFile zipFile, File storage) throws IOException {
        log(DEBUG, "Applying patch: " + patch.getId() + " / " + patch.getDescription());

        File startupFile = new File(karafBase, "etc/startup.properties");
        File overridesFile = new File(karafBase, "etc/overrides.properties");

        List<String> startup = readLines(new File(karafBase, "etc/startup.properties"));
        List<String> overrides = readLines(overridesFile);

        List<Artifact> toExtract = new ArrayList<Artifact>();
        List<Artifact> toDelete = new ArrayList<Artifact>();

        for (String bundle : patch.getBundles()) {

            Artifact artifact = mvnurlToArtifact(bundle, true);
            if (artifact == null) {
                continue;
            }

            // Compute patch bundle version and range
            VersionRange range;
            Version oVer = VersionTable.getVersion(artifact.getVersion());
            String vr = patch.getVersionRange(bundle);
            String override;
            if (vr != null && !vr.isEmpty()) {
                override = bundle + OVERRIDE_RANGE + vr;
                range = VersionRange.parseVersionRange(vr);
            } else {
                override = bundle;
                Version v1 = new Version(oVer.getMajor(), oVer.getMinor(), 0);
                Version v2 = new Version(oVer.getMajor(), oVer.getMinor() + 1, 0);
                range = new VersionRange(false, v1, v2, true);
            }

            // Process overrides.properties
            boolean matching = false;
            boolean added = false;
            for (int i = 0; i < overrides.size(); i++) {
                String line = overrides.get(i).trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    Artifact overrideArtifact = mvnurlToArtifact(line, true);
                    if (overrideArtifact != null) {
                        Version ver = VersionTable.getVersion(overrideArtifact.getVersion());
                        if (isSameButVersion(artifact, overrideArtifact) && range.contains(ver)) {
                            matching = true;
                            if (ver.compareTo(oVer) < 0) {
                                // Replace old override with the new one
                                overrides.set(i, override);
                                if (!added) {
                                    log(DEBUG, "Replacing with artifact: " + override);
                                    added = true;
                                }
                                // Remove old file
                                toDelete.add(overrideArtifact);
                                toExtract.remove(overrideArtifact);
                            }
                        }
                    } else {
                        log(WARN, "Unable to convert to artifact: " + line);
                    }
                }
            }
            // If there was not matching bundles, add it
            if (!matching) {
                overrides.add(override);
                log(DEBUG, "Adding artifact: " + override);
            }

            // Process startup.properties
            for (int i = 0; i < startup.size(); i++) {
                String line = startup.get(i).trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    int index = line.indexOf('=');
                    String mvnUrl = pathToMvnurl(line.substring(0, index));
                    if (mvnUrl != null) {
                        Artifact startupArtifact = mvnurlToArtifact(mvnUrl, true);
                        if (startupArtifact != null) {
                            Version ver = VersionTable.getVersion(startupArtifact.getVersion());
                            if (isSameButVersion(artifact, startupArtifact) && range.contains(ver)) {
                                matching = true;
                                // Now check versions
                                if (ver.compareTo(oVer) < 0) {
                                    line = artifact.getPath() + line.substring(index);
                                    startup.set(i, line);
                                    log(DEBUG, "Overwriting startup.properties with: " + artifact);
                                    added = true;
                                }
                            }
                        }
                    }
                }
            }

            // Extract artifact
            if (!matching || added) {
                toExtract.add(artifact);
            }
            // TODO: process framework ?
            // TODO: process lib folder ?

        }

        // Extract / delete artifacts if needed
        if (zipFile != null) {
            for (Artifact artifact : toExtract) {
                log(DEBUG, "Extracting artifact: " + artifact);
                ZipEntry entry = zipFile.getEntry("repository/" + artifact.getPath());
                if (entry == null) {
                    log(ERROR, "Could not find artifact in patch zip: " + artifact);
                    continue;
                }
                File f = new File(karafBase, "system/" + artifact.getPath());
                if (!f.isFile()) {
                    f.getParentFile().mkdirs();
                    InputStream fis = zipFile.getInputStream(entry);
                    FileOutputStream fos = new FileOutputStream(f);
                    try {
                        IOHelpers.copy(fis, fos);
                    } finally {
                        IOHelpers.close(fis, fos);
                    }
                }
            }
            for (Artifact artifact : toDelete) {
                String fileName = artifact.getPath();
                File file = new File(karafBase, "system/" + fileName);
                if (file.exists()) {
                    log(DEBUG, "Removing old artifact " + artifact);
                    file.delete();
                } else {
                    log(WARN, "Could not find: " + file);
                }
            }
        }

        overrides = new ArrayList<String>(new HashSet<String>(overrides));
        Collections.sort(overrides);
        writeLines(overridesFile, overrides);
        writeLines(startupFile, startup);

        // update the remaining patch files (using either the patch ZIP file or the patch storage location)
        if (zipFile != null) {
            patchFiles(patch, zipFile);
        } else if (storage != null) {
            patchFiles(patch, storage);
        } else {
            throw new PatchException("Unable to update patch files: no access to patch ZIP file or patch storage location");
        }

        if( patch.getMigratorBundle() !=null ) {
            Artifact artifact = mvnurlToArtifact(patch.getMigratorBundle(), true);
            if (artifact != null) {
                // Copy it to the deploy dir
                File src = new File(karafBase, "system/"+artifact.getPath());
                File target = new File(new File(karafBase, "deploy"), artifact.getArtifactId()+".jar");
                copy(src, target);
            }
        }
    }

    static String getJavaFile() throws IOException {
        String home = System.getProperty("java.home");
        if (home != null) {
            File file = new File(home, "bin/java");
            if (file.exists() && file.canExecute()) {
                return file.getCanonicalPath();
            }
        }
        return "java";
    }

    static Process exec(String[] args, File dir) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(args, new String[0], dir);
        startPump(process.getInputStream(), System.out);
        startPump(process.getErrorStream(), System.err);
        return process;
    }

    static Thread startPump(final InputStream is, final OutputStream os) {
        Thread thread = new Thread("io") {
            @Override
            public void run() {
                try {
                    pump(is, os);
                } catch (IOException e) {
                }
            }
        };
        thread.start();
        return thread;
    }

    static void pump(InputStream distro, OutputStream fos) throws IOException {
        int len;
        byte[] buffer = new byte[1024 * 4];
        while ((len = distro.read(buffer)) > 0) {
            fos.write(buffer, 0, len);
        }
    }

    /*
     * Patch files with the ones from the patch ZIP file
     */
    private void patchFiles(PatchData patch, ZipFile zipFile) throws IOException {
        for (String file : patch.getFiles()) {

            ZipEntry entry = zipFile.getEntry(file);
            if (entry == null) {
                log(ERROR, "Could not find file in patch zip: " + file);
                continue;
            }

            patchFile(patch, file, zipFile.getInputStream(entry));
        }
    }

    /*
     * Patch files with the ones from the patch storage location
     */
    private void patchFiles(PatchData patch, File storage) throws IOException {
        for (String file : patch.getFiles()) {

            File entry = new File(storage, file);
            if (!entry.exists()) {
                log(ERROR, "Could not find file in patch storage location: " + entry);
                continue;
            }

            patchFile(patch, file, new FileInputStream(entry));
        }
    }

    /*
     * Patch a single file
     */
    private void patchFile(PatchData patch, String file, InputStream is)  throws IOException {
        File target = new File(karafBase, file);
        if (target.exists()) {
            backup(patch, file);
            target.delete();
            log(DEBUG, String.format("Updating file: %s", file));
        } else {
            log(DEBUG, String.format("Adding file: %s", file));
        }
        target.getParentFile().mkdirs();
        FileOutputStream fos = new FileOutputStream(target);
        try {
            IOHelpers.copy(is, fos);
        } finally {
            IOHelpers.close(is, fos);
        }
    }

    private void backup(PatchData patch, String file) throws IOException {
        File backupDir = new File(new File(karafBase, PATCH_BACKUPS), patch.getId());

        File backup = new File(backupDir, file);
        backup.getParentFile().mkdirs();

        File source = new File(karafBase, file);
        copy(source, backup);
    }

    private void restore(PatchData patch, String file) throws IOException {
        File backupDir = new File(new File(karafBase, PATCH_BACKUPS), patch.getId());
        try {
            File backup = new File(backupDir, file);
            File original = new File(karafBase, file);
            if (backup.exists()) {
                log(DEBUG, String.format("Restoring previous version of file: %s", file));
                copy(backup, original);
            } else {
                log(DEBUG, String.format("Removing file: %s", file));
                original.delete();
            }
        } finally {
            backupDir.delete();
        }
    }

    protected void log(int level, String message) {
        logger.log(level, message);
    }

    protected String pathToMvnurl(String path) {
        String[] p = path.split("/");
        if (p.length >= 4 && p[p.length-1].startsWith(p[p.length-3] + "-" + p[p.length-2])) {
            String artifactId = p[p.length-3];
            String version = p[p.length-2];
            String classifier;
            String type;
            String artifactIdVersion = artifactId + "-" + version;
            StringBuffer sb = new StringBuffer();
            if (p[p.length-1].charAt(artifactIdVersion.length()) == '-') {
                classifier = p[p.length-1].substring(artifactIdVersion.length() + 1, p[p.length-1].lastIndexOf('.'));
            } else {
                classifier = null;
            }
            type = p[p.length-1].substring(p[p.length-1].lastIndexOf('.') + 1);
            sb.append("mvn:");
            for (int j = 0; j < p.length - 3; j++) {
                if (j > 0) {
                    sb.append('.');
                }
                sb.append(p[j]);
            }
            sb.append('/').append(artifactId).append('/').append(version);
            if (!"jar".equals(type) || classifier != null) {
                sb.append('/');
                if (!"jar".equals(type)) {
                    sb.append(type);
                }
                if (classifier != null) {
                    sb.append('/').append(classifier);
                }
            }
            return sb.toString();
        }
        return null;
    }

    static Artifact mvnurlToArtifact(String resourceLocation, boolean skipNonMavenProtocols) {
        resourceLocation = resourceLocation.replace("\r\n", "").replace("\n", "").replace(" ", "").replace("\t", "");
        final int index = resourceLocation.indexOf("mvn:");
        if (index < 0) {
            if (skipNonMavenProtocols) {
                return null;
            }
            throw new IllegalArgumentException("Resource URL is not a maven URL: " + resourceLocation);
        } else {
            resourceLocation = resourceLocation.substring(index + "mvn:".length());
        }
        // Truncate the URL when a '#', a '?' or a '$' is encountered
        final int index1 = resourceLocation.indexOf('?');
        final int index2 = resourceLocation.indexOf('#');
        int endIndex = -1;
        if (index1 > 0) {
            if (index2 > 0) {
                endIndex = Math.min(index1, index2);
            } else {
                endIndex = index1;
            }
        } else if (index2 > 0) {
            endIndex = index2;
        }
        if (endIndex >= 0) {
            resourceLocation = resourceLocation.substring(0, endIndex);
        }
        final int index3 = resourceLocation.indexOf('$');
        if (index3 > 0) {
            resourceLocation = resourceLocation.substring(0, index3);
        }

        String[] parts = resourceLocation.split("/");
        if (parts.length > 2) {
            String groupId = parts[0];
            String artifactId = parts[1];
            String version = parts[2];
            String type = "jar";
            String classifier = null;
            if (parts.length > 3) {
                type = parts[3];
                if (parts.length > 4) {
                    classifier = parts[4];
                }
            }
            return new Artifact(groupId, artifactId, version, type, classifier);
        }
        throw new IllegalArgumentException("Bad maven url: " + resourceLocation);
    }

    /*
     * Copy file
     */
    private static void copy(File from, File to) throws IOException {
        FileInputStream fis = null;
        FileOutputStream fos = null;

        try {
            fis = new FileInputStream(from);
            fos = new FileOutputStream(to);

            IOHelpers.copy(fis, fos);
        } finally {
            close(fis, fos);
        }
    }

    public static class Artifact {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String type;
        private final String classifier;

        public Artifact(String groupId, String artifactId, String version, String type, String classifier) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.type = type;
            this.classifier = classifier;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getVersion() {
            return version;
        }

        public String getType() {
            return type;
        }

        public String getClassifier() {
            return classifier;
        }

        public boolean hasClassifier() {
            return classifier != null;
        }

        public String getPath() {
            return groupId.replace('.', '/')
                    + '/'
                    + artifactId
                    + '/'
                    + version
                    + '/'
                    + artifactId
                    + (classifier != null ? "-" + classifier : "")
                    + '-'
                    + version
                    + '.'
                    + type;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(groupId)
                    .append(":")
                    .append(artifactId)
                    .append(":")
                    .append(version);
            if (!"jar".equals(type) || classifier != null) {
                sb.append(":").append(type);
                if (classifier != null) {
                    sb.append(":").append(classifier);
                }
            }
            return sb.toString();
        }

        public static boolean isSameButVersion(Artifact a1, Artifact a2) {
            return a1.getGroupId().equals(a2.getGroupId())
                    && a1.getArtifactId().equals(a2.getArtifactId())
                    && a1.hasClassifier() == a2.hasClassifier()
                    && (!a1.hasClassifier() || a1.getClassifier().equals(a2.getClassifier()))
                    && a1.getType().equals(a2.getType());
        }

    }
}
