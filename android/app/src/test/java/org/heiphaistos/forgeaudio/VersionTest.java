package org.heiphaistos.forgeaudio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VersionTest {

    @Test
    public void comparesDottedVersions() {
        assertTrue(MainActivity.isNewer("0.4.10", "0.4.9"));
        assertTrue(MainActivity.isNewer("1.0.0", "0.9.9"));
        assertTrue(MainActivity.isNewer("0.5", "0.4.9"));
        assertFalse(MainActivity.isNewer("0.4.2", "0.4.2"));
        assertFalse(MainActivity.isNewer("0.4.1", "0.4.2"));
        assertFalse(MainActivity.isNewer("", "0.4.2"));
        assertFalse(MainActivity.isNewer("0.4.3-beta", "0.4.3"));
    }
}
