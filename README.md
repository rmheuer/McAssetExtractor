# McAssetExtractor
 
#### Building

Make sure you have Apache Maven installed, then run `mvn package`
to build. The output JAR will be found in the `target/` directory.

#### Running

McAssetExtractor requires Java 1.8 or higher. Use
`java -jar McAssetExtractor.jar <version> <destination>` to extract
assets, replacing `<version>` with your desired version, and
`<destination>` with a path to the destination directory.
You can use the latest release or snapshot by setting the version to
`latest` or `latest-snapshot`, respectively.

This should give an output similar to the following:
```
Downloading assets for version 1.19
Extracting assets from client JAR
...
Downloading assets from launcher meta
...

Summary:
Extracted XXX assets from JAR
Extracted XXX assets from launcher meta
```

If any errors are present, make sure you are connected to the
internet, and that you have specified a valid version and
output directory.
