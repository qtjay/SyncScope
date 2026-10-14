package com.qtjay.syncscope.core.model;

import java.util.List;

/**
 * Entry of the global model tree returned by {@code GET /rest/db/browse}.
 */
public class BrowseEntry {

    public static final String TYPE_DIRECTORY = "FILE_INFO_TYPE_DIRECTORY";

    public String name;

    public String type;

    public long size;

    public String modTime;

    /** Only set when the API was called with levels > 0. */
    public List<BrowseEntry> children;

    public boolean isDirectory() {
        return TYPE_DIRECTORY.equals(type);
    }

    /**
     * @return full path relative to the folder root, using "/" as separator.
     */
    public String getPath(String parentPrefix) {
        if (parentPrefix == null || parentPrefix.isEmpty()) {
            return name;
        }
        return parentPrefix + "/" + name;
    }
}
