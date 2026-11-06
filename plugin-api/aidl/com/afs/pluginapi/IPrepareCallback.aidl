package com.afs.pluginapi;

import com.afs.pluginapi.PrepareResult;

oneway interface IPrepareCallback {
    void onResult(in PrepareResult result);
}
