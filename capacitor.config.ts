import type { CapacitorConfig } from '@capacitor/cli';

/**
 * The app shows your Forge Audio server (login, player, library…).
 * - FORGE_SERVER_URL set at build time (e.g. https://musique.mon-vps.fr): the app opens it directly.
 * - Otherwise the bundled page (www/) asks for the server address at first launch.
 */
const server = process.env.FORGE_SERVER_URL?.trim().replace(/\/+$/, '');

const config: CapacitorConfig = {
  appId: 'org.heiphaistos.forgeaudio',
  appName: 'Forge Audio',
  webDir: 'www',
  backgroundColor: '#0b0908',
  // Lets the web app know it runs inside the mobile app (server switch, notification controls).
  appendUserAgent: 'ForgeAudioApp',
  server: {
    ...(server ? { url: server } : {}),
    androidScheme: 'https',
    // The server address is chosen by the user: allow navigating to it (ForgeWeb.shouldOverrideLoad sends other sites to the browser).
    allowNavigation: ['*'],
    // Allow http:// servers on a local network.
    cleartext: true,
    // Server unreachable (offline, server down): bundled page with Retry / Change server instead of a browser error.
    errorPath: 'error.html',
  },
  plugins: {
    // Light icons on the dark status and navigation bars. Insets are applied natively (MainActivity):
    // with a recent WebView and viewport-fit=cover, Capacitor's 'css' mode lets the page draw under the bars.
    SystemBars: { style: 'DARK', insetsHandling: 'disable' },
  },
  android: {
    // The setup screen (https://localhost) must reach http:// servers on a local network.
    allowMixedContent: true,
    captureInput: true,
  },
  ios: {
    contentInset: 'never',
    limitsNavigationsToAppBoundDomains: false,
  },
};

export default config;
