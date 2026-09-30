# 🔥 Forge Audio — Android & iOS

Application mobile de [Forge Audio](https://github.com/Heiphaistos/Forge-audio-), le lecteur musical libre et sans pub. Elle se connecte à **votre serveur Forge Audio** (le même que la version web) : même compte, mêmes playlists, même historique, sur téléphone, ordinateur et navigateur.

| | Android | iPhone / iPad |
|---|---|---|
| Fichier | `ForgeAudio-android.apk` | `ForgeAudio-ios-unsigned.ipa` |
| Installation | directe (APK) | à signer (voir [iOS](#-iphone--ipad)) |
| Musique écran éteint / autre app | ✅ | ✅ |
| Commandes dans la notification | ✅ précédent, lecture/pause, suivant, fermer, pochette | écran de verrouillage / Centre de contrôle |
| Écran de verrouillage, casque, Bluetooth, montre | ✅ | ✅ |

## ✨ Ce que fait l'application

- Affiche l'interface complète de Forge Audio (connexion, recherche YouTube / SoundCloud / Dailymotion / Spotify…, playlists, paroles, vidéo, égaliseur…).
- **La musique continue en arrière-plan** : écran verrouillé, dans une autre application, ou après avoir appuyé sur Retour. Sur Android, un service de lecture au premier plan (notification « Lecture en cours ») empêche le système de la couper, et un verrou Wi-Fi évite les coupures de flux.
- **Notification média** (Android) avec la pochette, une **barre de progression déplaçable** et les boutons ⏮ ⏯ ⏭ ✕ ; commandes de l'écran de verrouillage, du casque et du Bluetooth.
- Se comporte comme une vraie application de musique : **pause pendant un appel** (reprise après), **pause quand le casque est débranché** ou le Bluetooth déconnecté.
- **Partager → Forge Audio** depuis YouTube, Spotify, SoundCloud, Deezer… : le titre est lu, une playlist est importée.
- **Retour** ferme d'abord le panneau ouvert (lecteur plein écran, menu, égaliseur…) puis revient à la vue précédente ; sur l'écran d'accueil il réduit l'application sans couper la musique.
- **Vidéo en plein écran** (paysage, barres masquées ; Retour pour sortir).
- **Téléchargements** (MP3, audio, vidéo) et **export** de la bibliothèque enregistrés dans *Téléchargements*.
- Les liens vers d'autres sites (source YouTube, GitHub…) s'ouvrent dans le navigateur, pas dans l'application.
- **Hors ligne ou serveur arrêté** : page « Serveur injoignable » avec *Réessayer* et *Changer de serveur*, nouvel essai automatique au retour du réseau.
- **Mises à jour** : l'application vérifie une fois par jour s'il existe une version plus récente et propose de la télécharger.
- Au premier lancement, l'application demande l'**adresse du serveur**, préremplie avec `https://connect.forgeaudio.heiphaistos.org` ; on peut en changer dans *Paramètres → Application mobile*. Les serveurs `http://` d'un réseau local sont acceptés.

> La musique s'arrête seulement si vous **fermez l'application depuis les applications récentes** (balayage) ou avec le bouton ✕ de la notification.

## 📲 Installer

Les fichiers sont dans les [Releases](../../releases/latest).

### Android

1. Téléchargez l'APK sur le téléphone : **https://forgeaudio.heiphaistos.org/ForgeAudio-android.apk** (ou `ForgeAudio-android.apk` dans les Releases). Si Chrome affiche « Ce fichier peut être dangereux », appuyez sur **Télécharger quand même** : sans cette confirmation, le téléchargement reste bloqué à 100 %.
2. Ouvrez-le ; Android demande d'autoriser l'installation depuis le navigateur ou le gestionnaire de fichiers : acceptez, puis **Installer**.
3. Lancez **Forge Audio**, entrez l'adresse de votre serveur, connectez-vous.
4. Acceptez les **notifications** (Android 13+) : c'est la notification qui garde la musique en arrière-plan et donne les commandes.

Conseil : sur certains téléphones (Xiaomi, Huawei, Samsung…), désactivez l'« optimisation de la batterie » pour Forge Audio (*Paramètres → Applications → Forge Audio → Batterie → Aucune restriction*) pour de longues écoutes écran éteint.

Les mises à jour s'installent par-dessus (mêmes données) tant que les APK sont signés avec la même clé (voir [Signature](#-signature-android)). Les versions jusqu'à la 0.4.1 étaient signées avec la clé partagée : désinstallez-les une fois avant d'installer la 0.4.2 ou plus récente.

**Désinstaller** : appui long sur l'icône Forge Audio → *Désinstaller* (ou *Paramètres → Applications → Forge Audio → Désinstaller*). Le compte et les playlists restent sur le serveur.

### 📱 iPhone / iPad

Apple n'autorise l'installation que d'applications signées. Trois possibilités :

1. **Sideloadly** ou **AltStore** (gratuit, avec un simple identifiant Apple) : installez `ForgeAudio-ios-unsigned.ipa` depuis un ordinateur. Avec un compte gratuit, l'application doit être re-signée tous les 7 jours (AltStore le fait automatiquement).
2. **TestFlight / App Store** (compte Apple Developer, 99 €/an) : ouvrez `ios/App/App.xcodeproj` dans Xcode sur un Mac, choisissez votre équipe dans *Signing & Capabilities*, puis *Product → Archive → Distribute*.
3. **Sans installation** : ouvrez votre serveur Forge Audio dans Safari → Partager → *Sur l'écran d'accueil*. Plus simple, mais iOS peut couper la musique en arrière-plan plus tôt qu'avec l'application.

## 🌐 Mettre les applications en ligne sur votre site

Chaque release contient des fichiers au **nom fixe** ; ces liens pointent toujours vers la dernière version :

```
https://github.com/Heiphaistos/Forge-Audio-Android/releases/latest/download/ForgeAudio-android.apk
https://github.com/Heiphaistos/Forge-Audio-Android/releases/latest/download/ForgeAudio-ios-unsigned.ipa
```

(remplacez `Heiphaistos/Forge-Audio-Android` par le nom exact du dépôt s'il diffère.)

Exemple de boutons à mettre sur une page du site :

```html
<a href="https://forgeaudio.heiphaistos.org/ForgeAudio-android.apk">Télécharger pour Android</a>
<a href="https://github.com/Heiphaistos/Forge-Audio-Android/releases/latest">iPhone / iPad (instructions)</a>
<a href="https://github.com/Heiphaistos/Forge-audio-/releases/latest">Windows · macOS · Linux</a>
```

Pour héberger l'APK sur votre propre serveur plutôt que GitHub, téléchargez-le après chaque release et placez-le dans un dossier servi par nginx, par exemple :

```bash
curl -L -o /var/www/forge-audio/ForgeAudio-android.apk \
  https://github.com/Heiphaistos/Forge-Audio-Android/releases/latest/download/ForgeAudio-android.apk
```

```nginx
location = /ForgeAudio-android.apk {
    root /var/www/forge-audio;
    types { application/vnd.android.package-archive apk; }
}
```

La page *Paramètres* de la version web de Forge Audio affiche déjà des boutons de téléchargement vers ces releases (constante `MOBILE_RELEASES` dans `web/src/views/Settings.tsx` du dépôt principal, à ajuster si le nom du dépôt change).

## 🏗️ Compilation (automatique)

Le workflow **Applications mobiles** (`.github/workflows/build.yml`) compile à chaque push :

- **Android** (Ubuntu) : `ForgeAudio-<version>-android.apk`, `ForgeAudio-android.apk` (nom fixe) et `ForgeAudio-<version>-android.aab` (Play Store).
- **iOS** (macOS) : `ForgeAudio-<version>-ios-unsigned.ipa` et `ForgeAudio-ios-unsigned.ipa`.

Les fichiers sont téléchargeables dans l'onglet *Actions* (run → *Artifacts*). Pour publier une release :

```bash
# mettre à jour "version" dans package.json, puis
git tag v0.4.0 && git push origin v0.4.0
```

### Intégrer l'adresse du serveur

Pour que l'application ouvre directement votre serveur (sans écran de configuration) :

- une fois pour toutes : *Settings → Secrets and variables → Actions → Variables* → `FORGE_SERVER_URL` = `https://musique.mon-vps.fr` ;
- ou pour une compilation : *Actions → Applications mobiles → Run workflow* → champ *server_url*.

### 🔐 Signature Android

- **Sans configuration**, l'APK est signé avec la clé *partagée* du dépôt (`keystore/forge-audio-shared.jks`, mot de passe public). Pratique pour démarrer, mais n'importe qui pourrait signer un APK « compatible » : à remplacer.
- **Recommandé** : ajoutez 4 secrets (*Settings → Secrets and variables → Actions → Secrets*) :

| Secret | Contenu |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | le fichier `.jks` encodé en base64 (`base64 -w0 release.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | mot de passe du keystore |
| `ANDROID_KEY_ALIAS` | alias de la clé |
| `ANDROID_KEY_PASSWORD` | mot de passe de la clé |

Créer une clé : `keytool -genkeypair -keystore release.jks -alias forge-audio-release -keyalg RSA -keysize 4096 -validity 10000`. **Conservez-la précieusement** : sans elle, impossible de publier des mises à jour installables par-dessus.

> Changer de clé oblige à désinstaller l'ancienne application une fois (les données sont sur le serveur, rien n'est perdu).

### Publier sur le Google Play Store

1. Compte développeur Google Play (25 $ une fois).
2. Créez l'application, remplissez la fiche (description, captures, politique de confidentialité).
3. Envoyez `ForgeAudio-<version>-android.aab` dans une piste de test puis en production. Play App Signing gère la clé de distribution ; la clé de vos secrets devient la clé d'importation.

## 🛠️ Développement local

Prérequis : Node.js 20+, JDK 21 et Android Studio (Android), Xcode 16+ sur macOS (iOS).

```bash
npm install
FORGE_SERVER_URL=http://192.168.1.10:8787 npx cap sync   # adresse facultative
npx cap open android    # Android Studio → Run
npx cap open ios        # Xcode → Run
```

### Structure

```
capacitor.config.ts        configuration (identifiant org.heiphaistos.forgeaudio, serveur, navigation)
www/index.html             écran « adresse du serveur » embarqué
android/…/MainActivity.java   WebView gardée active en arrière-plan, Retour = arrière-plan,
                              relais entre la notification et le lecteur web
android/…/PlaybackService.java service de lecture au premier plan, MediaSession, notification média
ios/App/App/AppDelegate.swift  session audio « lecture » ; Info.plist : UIBackgroundModes = audio
keystore/                  clé de signature partagée (repli sans secrets)
```

Le lecteur web expose `window.__forgeNowPlaying()` (titre en cours) et `window.__forgeRemote(action)` (`toggle`, `play`, `pause`, `next`, `prev`) : l'application Android les utilise pour la notification, l'application de bureau pour sa zone de notification.

## Licence

MIT — voir le dépôt principal.
