package com.afs.pluginapi;

import com.afs.pluginapi.OpenResult;

oneway interface IOpenCallback {
    void onResult(in OpenResult result);
}
