package com.afs.pluginapi;

/**
 * Opaque death anchor only. It intentionally carries no business events and
 * no data. The host links to this binder and invalidates the page lease when
 * the plugin process dies.
 */
oneway interface IPageClient {
}
