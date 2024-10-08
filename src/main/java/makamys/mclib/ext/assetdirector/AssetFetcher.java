package makamys.mclib.ext.assetdirector;

import static makamys.mclib.ext.assetdirector.AssetDirector.LOGGER;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.commons.io.FileUtils;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import makamys.mclib.ext.assetdirector.mc.MCUtil.Version;

/** Responsible for the implementation details of fetching assets, most notably interfacing with Mojang's API. */
public class AssetFetcher {
    
    final static String MANIFEST_ENDPOINT = System.getProperty("assetDirector.manifestEndpoint", "https://launchermeta.mojang.com/mc/game/version_manifest.json");
    final static String RESOURCES_ENDPOINT = System.getProperty("assetDirector.resourcesEndpoint", "https://resources.download.minecraft.net");
    
    final static String ASSET_INDEX_PATH = "assets/indexes/%s.json";
    final static String CLIENT_JAR_PATH = "versions/%s/%s.jar";
    final static String VERSION_INDEX_PATH = "versions/%s/%s.json";
    
    private static final int DOWNLOAD_TIMEOUT = Integer.parseInt(System.getProperty("assetDirector.downloadTimeout", "10000")), // ms
                             DOWNLOAD_ATTEMPTS = Integer.parseInt(System.getProperty("assetDirector.downloadAttempts", "3"));
    
    private static JsonObject manifest;
    public Map<String, VersionIndex> versionIndexes = new HashMap<>();
    public Map<String, AssetIndex> assetIndexes = new HashMap<>();
    
    private Map<String, File> fileMap = new HashMap<>();
    private static final File NULL_FILE = new File("");
    
    public File assetsDir, adDir;
    
    public AssetFetcher(File assetsDir, File adDir) {
        this.assetsDir = assetsDir;
        this.adDir = adDir;
        if(!adDir.exists()) {
        	adDir.mkdirs();
        }
    }
    
    public void init() {
        LOGGER.info("Using directory " + adDir);
        
        // clean up incomplete downloads
        Arrays.stream(adDir.listFiles((dir, name) -> name.endsWith(".part"))).forEach(f -> f.delete());
        
        SSLHacker.hack();
    }

    public void fetchAsset(String version, String asset) throws Exception {
        loadVersionDeps(version);
        String hash = getAssetHash(version, asset);
        fetchAssetByHash(hash);
    }
    
    public void fetchAssetByHash(String assetHash) throws Exception {
        if(assetHash != null) {
            downloadAssetByHash(assetHash);
        }
    }
    
    public boolean needsFetchResource(String version, String asset) {
        return needsFetchAsset(version, asset, false);
    }
    
    public boolean needsFetchAsset(String version, String asset, boolean printErrors) {
        String hash = getAssetHash(version, asset, printErrors);
        return needsFetchAssetByHash(hash);
    }
    
    public boolean needsFetchAssetByHash(String assetHash) {
        if(assetHash != null) {
            return !fileIsPresent(assetHash);
        }
        return false;
    }
    
    @Nullable
    public String getAssetHash(String version, String asset) {
        return getAssetHash(version, asset, false);
    }
    
    @Nullable
    public String getAssetHash(String version, String asset, boolean printErrors) {
        VersionIndex vi = versionIndexes.get(version);
        AssetIndex assetIndex = assetIndexes.get(vi.assetsId);
        String hash = assetIndex.nameToHash.get(asset);
        if(hash == null && printErrors) {
            LOGGER.error("Couldn't find asset " + asset + " inside " + version + " asset index");
        }
        return hash;
    }
    
    public boolean fileIsPresent(String hash) {
        return getAssetFileForRead(hash) != NULL_FILE;
    }
    
    public void fetchForAllVersions(String asset) throws Exception {
        for(String v : assetIndexes.keySet()) {
            fetchAsset(v, asset);
        }
    }
    
    private void downloadAssetByHash(String hash) throws IOException {
        String relPath = "/" + hash.substring(0, 2) + "/" + hash;
        File outFile = getAssetFileForWrite(hash);
        File outFileTmp = new File(adDir, outFile.getName() + ".part");
        
        for(int i = 0; i < DOWNLOAD_ATTEMPTS; i++) {
            copyURLToFile(RESOURCES_ENDPOINT + relPath, outFileTmp);
            if(hash.equals(getSha1(outFileTmp))) {
                // OK
                outFile.getParentFile().mkdirs();
                outFileTmp.renameTo(outFile);
                fileMap.put(hash, outFile);
                break;
            } else {
                LOGGER.warn("Got invalid hash when downloading " + hash + ". Attempt " + (i + 1) + "/" + DOWNLOAD_ATTEMPTS);
            }
        }
    }
    
    /** Loads manifest, version index, asset index and client jar for the given version as needed. */
    public void loadVersionDeps(String version) throws Exception {
        if(versionIndexes.containsKey(version)) return;
        
        // TODO redownload stuff if timestamp in manifest changes?
        File indexJson = new File(adDir, String.format(VERSION_INDEX_PATH, version, version));
        if(!indexJson.exists()) {
            downloadVersionIndex(version, indexJson);
        }
        VersionIndex vi = new VersionIndex(loadJson(indexJson, JsonObject.class));
        versionIndexes.put(version, vi);
        
        if(!assetIndexes.containsKey(vi.assetsId)) {
            File assetIndex = new File(adDir, String.format(ASSET_INDEX_PATH, vi.assetsId));
            if(!assetIndex.exists()) {
                String url = vi.json.get("assetIndex").getAsJsonObject().get("url").getAsString();
                copyURLToFile(url, assetIndex);
            }
            assetIndexes.put(vi.assetsId, new AssetIndex(loadJson(assetIndex, JsonObject.class)));
        }
    }
    
    public void fetchJar(String version) throws IOException {
        versionIndexes.get(version).fetchJar(version);
    }
    
    public void loadJar(String version) throws IOException {
        versionIndexes.get(version).loadJar(version);
    }
    
    public boolean needsFetchJar(String version) {
        return !new File(adDir, String.format(AssetFetcher.CLIENT_JAR_PATH, version, version)).exists();
    }
    
    private void downloadVersionIndex(String version, File dest) throws Exception {
        if(manifest == null) {
            manifest = downloadJson(MANIFEST_ENDPOINT, JsonObject.class);
        }
        
        for(JsonElement verElem : manifest.get("versions").getAsJsonArray()) {
            ManifestVersionJSON ver = new Gson().fromJson(verElem, ManifestVersionJSON.class);
            if(ver.id.equals(version)) {
                copyURLToFile(ver.url, dest);
                return;
            }
        }
        LOGGER.error("Game version " + version + " could not be found in manifest json.");
    }
    
    private void copyURLToFile(String source, File destination) throws IOException {
        try {
            URL url = new URL(source);
            LOGGER.trace("Downloading " + url + " to " + destination);
            FileUtils.copyURLToFile(url, destination, DOWNLOAD_TIMEOUT, DOWNLOAD_TIMEOUT);
        } catch(IOException e) {
            LOGGER.error("Failed to download " + source + " to " + destination);
            throw e;
        }
    }
    
    private <T> T downloadJson(String urlStr, Class<T> classOfT) throws Exception {
        try {
            URL url = new URL(urlStr);
            LOGGER.trace("Downloading JSON at " + url);
            return loadJson(url.openStream(), classOfT);
        } catch(Exception e) {
            LOGGER.error("Failed to download JSON at " + urlStr);
            throw e;
        }
    }
    
    private <T> T loadJson(File file, Class<T> classOfT) throws Exception {
        return loadJson(new FileInputStream(file), classOfT);
    }
    
    private <T> T loadJson(InputStream stream, Class<T> classOfT) throws Exception {
        return new Gson().fromJson(new InputStreamReader(new BufferedInputStream(stream)), classOfT);
    }
    
    public File getAssetFileForRead(String hash) {
        File file = fileMap.get(hash);
        if(file == null) {
            File fileUpper = new File(adDir, "assets/objects/" + hash.substring(0, 2) + "/" + hash);
            File fileLower = new File(assetsDir, "objects/" + hash.substring(0, 2) + "/" + hash);
            
            file = fileUpper.exists() ? fileUpper : fileLower.exists() ? fileLower : NULL_FILE;
            fileMap.put(hash, file);
        }
        return file;
    }
    
    public File getAssetFileForWrite(String hash) {
        return new File(adDir, "assets/objects/" + hash.substring(0, 2) + "/" + hash);
    }
    
    public InputStream getAssetInputStream(String hash) throws IOException {
        return new BufferedInputStream(new FileInputStream(getAssetFileForRead(hash)));
    }
    
    public InputStream getAssetInputStream(String version, String path) throws IOException {
        return getAssetInputStream(assetIndexes.get(versionToAssetsId(version)).nameToHash.get(path));
    }
    
    public boolean hashExists(String hash) {
        if(hash == null) return false;
        
        File file = fileMap.get(hash);
        return file != null && file != NULL_FILE;
    }
    
    static String getSha1(File file) {
        try {
            return Files.hash(file, Hashing.sha1()).toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public String versionToAssetsId(String version) {
        try {
            loadVersionDeps(version);
        } catch(Exception e) {
            e.printStackTrace();
        }
        
        return versionIndexes.get(version).assetsId;
    }
    
    private static class ManifestVersionJSON {
        String id;
        String url;
    }
    
    public static class AssetIndex {
        public JsonObject json;
        public Map<String, String> nameToHash = new HashMap<>();
        
        public AssetIndex(JsonObject json) {
            this.json = json;
            json.get("objects").getAsJsonObject().entrySet().forEach(e -> {
                String name = e.getKey();
                String hash = e.getValue().getAsJsonObject().get("hash").getAsString();
                nameToHash.put(name, hash);
            });
        }
    }
    
    public class VersionIndex {
        public JsonObject json;
        public String assetsId;
        JarFile jar;
        public Set<String> jarContents;
        public Version version;
        
        public VersionIndex(JsonObject json) {
            this.json = json;
            assetsId = json.get("assets").getAsString();
            this.version = new Version(json.get("id").getAsString());
        }
        
        public void fetchJar(String version) throws IOException {
            if(jar != null) return;
            
            File clientJar = new File(adDir, String.format(CLIENT_JAR_PATH, version, version));
            
            String url = json.get("downloads").getAsJsonObject().get("client").getAsJsonObject().get("url").getAsString();
            copyURLToFile(url, clientJar);   
        }
        
        public void loadJar(String version) throws IOException {
            if(jar != null) return;
            
            File clientJar = new File(adDir, String.format(CLIENT_JAR_PATH, version, version));
            
            this.jar = new JarFile(clientJar);
            jarContents = jar.stream().map(e -> e.getName()).collect(Collectors.toSet());
        }
        
        public boolean jarContainsFile(String path) {
            return jarContents != null && jarContents.contains(path);
        }
        
        public InputStream getJarFileStream(String path) throws IOException {
            return jar.getInputStream(jar.getEntry(path));
        }
    }
    
}
