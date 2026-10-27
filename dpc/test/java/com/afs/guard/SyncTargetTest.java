package com.afs.guard;

public final class SyncTargetTest {
    private static void reject(String owner, String id) {
        try { SyncTarget.create(owner, id); throw new AssertionError("accepted invalid target"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        SyncTarget t = SyncTarget.create(" User-1 ", " ABCDEF012345 ");
        if (!t.apiUrl().equals("https://api.github.com/gists/abcdef012345")) throw new AssertionError();
        if (!t.rawUrl("study-gate.json").equals("https://gist.githubusercontent.com/User-1/abcdef012345/raw/study-gate.json")) throw new AssertionError();
        reject("../other", "abcdef"); reject("a--b", "abcdef");
        reject("user", "abcdef/other"); reject("user", "");
        try { t.rawUrl("../secret"); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        System.out.println("SyncTargetTest PASS");
    }
}
