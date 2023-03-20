package com.github.rmheuer.mcasset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class McAssetExtractor {
    private static final String VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String DOWNLOAD_URL_PREFIX = "https://resources.download.minecraft.net/";

    private void run(String version, File outDir) {
        String versionInfoUrl = findVersionInfoUrl(version);

        JsonObject versionInfo = null;
        try {
            versionInfo = downloadJson(versionInfoUrl).getAsJsonObject();
        } catch (IOException e) {
            exitError("Failed to download version info");
        }

        byte[] clientJar = downloadClientJar(versionInfo);
        int[] jarResult = extractJarAssets(clientJar, outDir);

        JsonObject assetIndex = getAssetIndex(versionInfo);
        int[] metaResult = extractMetaAssets(assetIndex, outDir);

        printSummary(jarResult, metaResult);
    }

    private String findVersionInfoUrl(String version) {
        JsonObject manifest = null;
        try {
            manifest = downloadJson(VERSION_MANIFEST_URL).getAsJsonObject();
        } catch (IOException e) {
            e.printStackTrace();
            exitError("Failed to download version manifest");
        }

        boolean latestRelease = version.equals("latest");
        boolean latestSnapshot = version.equals("latest-snapshot");
        if (latestRelease || latestSnapshot) {
            JsonObject latest = manifest.getAsJsonObject("latest");
            if (latestRelease)
                version = latest.get("release").getAsString();
            else
                version = latest.get("snapshot").getAsString();
        }

        System.out.println("Downloading assets for version " + version);

        JsonArray versions = manifest.getAsJsonArray("versions");
        for (JsonElement elem : versions) {
            JsonObject obj = elem.getAsJsonObject();

            if (!obj.get("id").getAsString().equals(version))
                continue;

            return obj.get("url").getAsString();
        }

        exitError("Failed to find version info url for version " + version);
        return null;
    }

    private JsonObject getAssetIndex(JsonObject versionInfo) {
        JsonObject assetIndex = versionInfo.getAsJsonObject("assetIndex");
        String url = assetIndex.get("url").getAsString();

        try {
            return downloadJson(url).getAsJsonObject();
        } catch (IOException e) {
            e.printStackTrace();
            exitError("Failed to download asset index");
        }

        return null;
    }

    private byte[] downloadClientJar(JsonObject versionInfo) {
        JsonObject downloads = versionInfo.getAsJsonObject("downloads");
        JsonObject client = downloads.getAsJsonObject("client");
        String url = client.get("url").getAsString();

        try {
            return download(url);
        } catch (IOException e) {
            e.printStackTrace();
            exitError("Failed to download client JAR");
        }

        return null;
    }

    private File checkAndCreateFile(File outputDir, ZipEntry entry) throws IOException {
        File destFile = new File(outputDir, entry.getName());

        String dirPath = outputDir.getCanonicalPath();
        String filePath = destFile.getCanonicalPath();

        if (!filePath.startsWith(dirPath + File.separator)) {
            throw new IOException("Entry outside target: " + entry.getName());
        }

        return destFile;
    }

    private String formatException(Exception e) {
        return " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
    }

    private int[] extractJarAssets(byte[] clientData, File outputDir) {
        System.out.println("Extracting assets from client JAR");

        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(clientData));
        ZipEntry entry;
        int count = 0;
        int fail = 0;
        try {
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().startsWith("assets"))
                    continue;

                File file = checkAndCreateFile(outputDir, entry);
                if (entry.isDirectory()) {
                    if (!file.isDirectory() && !file.mkdirs())
                        throw new IOException("Failed to create directory " + file);
                } else {
                    File parent = file.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs())
                        throw new IOException("Failed to create directory " + parent);

                    try {
                        System.out.println("Extracting from JAR: " + entry.getName());
                        copyStreams(zip, new FileOutputStream(file), false);
                        count++;
                    } catch (IOException e) {
                        System.err.println("Failed to extract " + entry.getName() + formatException(e));
                        fail++;
                    }
                }
            }
            zip.closeEntry();
            zip.close();
        } catch (IOException e) {
            e.printStackTrace();
            exitError("Failed to unzip client JAR");
        }

        return new int[] {count, fail};
    }

    private int[] extractMetaAssets(JsonObject assetIndex, File outputDir) {
        System.out.println("Downloading assets from launcher meta");
        int count = 0;
        int fail = 0;

        JsonObject objects = assetIndex.getAsJsonObject("objects");
        File assetsDir = new File(outputDir, "assets");
        for (String key : objects.keySet()) {
            System.out.println("Downloading " + key + " from launcher meta");
            File targetFile = new File(assetsDir, key);
            if (targetFile.exists())
                System.out.println("Overwriting " + targetFile);
            File parentFile = targetFile.getParentFile();
            if (!parentFile.exists() && !parentFile.mkdirs()) {
                System.err.println("Failed to create directory " + parentFile);
                continue;
            }

            String hash = objects.getAsJsonObject(key).get("hash").getAsString();
            String block = hash.substring(0, 2);

            String url = DOWNLOAD_URL_PREFIX + block + "/" + hash;
            try {
                copyStreams(getDownloadStream(url), new FileOutputStream(targetFile), true);
                count++;
            } catch (IOException e) {
                e.printStackTrace();
                System.err.println("Failed to download " + key + " from " + url + formatException(e));
                fail++;
            }
        }

        return new int[] {count, fail};
    }

    private void printSummary(int[] jar, int[] meta) {
        System.out.println();
        System.out.println("Summary:");
        System.out.println("Extracted " + jar[0] + " assets from JAR (" + jar[1] + " failed)");
        System.out.println("Extracted " + meta[0] + " assets from launcher meta (" + jar[1] + " failed)");
    }

    private JsonElement downloadJson(String url) throws IOException {
        return JsonParser.parseReader(new InputStreamReader(getDownloadStream(url)));
    }

    private byte[] download(String url) throws IOException {
        return streamToByteArray(getDownloadStream(url));
    }

    private InputStream getDownloadStream(String url) throws IOException {
        //System.out.println("Downloading " + url);
        return new URL(url).openStream();
    }

    private byte[] streamToByteArray(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        copyStreams(in, b, true);
        return b.toByteArray();
    }

    private void copyStreams(InputStream in, OutputStream out, boolean closeInput) throws IOException {
        byte[] buffer = new byte[1024];
        int read;

        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }

        if (closeInput)
            in.close();
        out.close();
    }

    private static void exitError(String message) {
        System.err.println(message);
        System.exit(1);
    }

    public static void main(String[] args) {
        if (args.length < 2)
            exitError("Expected arguments: <version> <destination>");

        String version = args[0];
        String outPath = args[1];
        File outDir = new File(outPath);
        if (outDir.exists())
            exitError("Output directory already exists");

        new McAssetExtractor().run(version, outDir);
    }
}
