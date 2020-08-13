package org.nd4j.resources;

import java.io.File;
import java.io.InputStream;

/**
 * Resolver interface: used to resolve a path (or directory path) to an actual file.
 * This is mainly used to find test resources which need to be downloaded on-demand from a remote location, and
 * (if appropriate) cached locally.
 *
 * @author Alex Black
 */
public interface Resolver {

    /**
     * Priority of this resolver. 0 is highest priority (check first), larger values are lower priority (check last)
     */
    int priority();

    /**
     * Determine if the specified file resource can be resolved by {@link #asFile(String)} and {@link #asStream(String)}
     *
     * @param resourcePath Path of the resource to be resolved
     * @return True if this resolver is able to resolve the resource file - i.e., whether it is a valid path and exists
     */
    boolean exists(String resourcePath);

    /**
     * Determine if the specified directory resource can be resolved by {@link #copyDirectory(String, File)}
     *
     * @param dirPath Path of the directory resource to be resolved
     * @return True if this resolver is able to resolve the directory - i.e., whether it is a valid path and exists
     */
    boolean directoryExists(String dirPath);

    /**
     * Get the specified resources as a standard local file.
     * Note that the resource must exist as determined by {@link #exists(String)}
     *
     * @param resourcePath Path of the resource.
     * @return The local file version of the resource
     */
    File asFile(String resourcePath);

    /**
     * Get the specified resources as an input stream.
     * Note that the resource must exist as determined by {@link #exists(String)}
     *
     * @param resourcePath Path of the resource.
     * @return The resource as an input stream
     */
    InputStream asStream(String resourcePath);

    /**
     * Copy the directory resource (recursively) to the specified destination directory
     *
     * @param dirPath        Path of the resource directory to resolve
     * @param destinationDir Where the files should be copied to
     */
    void copyDirectory(String dirPath, File destinationDir);

    /**
     * @return True if the resolver has a local cache directory, as returned by {@link #localCacheRoot()}
     */
    boolean hasLocalCache();

    /**
     * @return Root directory of the local cache, or null if {@link #hasLocalCache()} returns false
     */
    File localCacheRoot();

}
