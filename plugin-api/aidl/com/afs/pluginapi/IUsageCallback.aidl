package com.afs.pluginapi;

import com.afs.pluginapi.UsageResult;

oneway interface IUsageCallback {
    void onResult(in UsageResult result);
}
