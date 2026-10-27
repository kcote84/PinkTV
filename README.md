# Pink TV

Lecteur Xtream Codes autonome pour téléphone Android et Fire TV / Firestick. Interface française rose, navigation tactile et télécommande. Aucune chaîne et aucun abonnement inclus.

## Installation

Télécharger **PinkTV.apk** dans [la dernière version](https://github.com/kcote84/PinkTV/releases/latest). Android 8 ou plus récent. Sur téléphone, autoriser l’installation depuis le navigateur ou le gestionnaire de fichiers. Sur Fire TV, transférer l’APK puis autoriser son installation dans les options développeur. Le paquet `ca.pinktv` est indépendant des autres applications.

Ouvrir **Réglages → Ajouter un compte Xtream**, puis saisir l’URL du serveur (et son port), le nom d’utilisateur et le mot de passe de son propre abonnement. Le compte est chiffré avec Android Keystore. Utiliser HTTPS lorsque le fournisseur le propose; les fournisseurs HTTP sont aussi acceptés.

## Au quotidien

- **Télé** : guide horaire, recherche, catégories, Québec, sports et favoris. Toucher une émission pour ses actions; télécommande : flèches, OK et Menu. Glisser sur le guide pour changer de page ou d’horaire.
- **Films et Séries** : affiches, résumés, saisons et épisodes, reprise et historique local. La bibliothèque principale privilégie les contenus en français, comme le menu d’origine.
- **Mes favoris** : chaînes, films, séries et épisodes, conservés sur cet appareil et séparés par compte. Appui long ou Menu pour les options.
- **Enregistrements** : choisir une émission dans le guide, ou **Enregistrer** pendant une chaîne pour 30, 60 ou 120 minutes. Les fichiers MPEG-TS restent dans le stockage privé de l’appareil, sans PC ni serveur compagnon.
- **Mises à jour** : disponibles dans Réglages; le téléchargement est vérifié par SHA-256, nom de paquet, version et signature.

Sur téléphone, l’accueil passe en deux colonnes, les affiches restent lisibles en portrait et les commandes défilent horizontalement. Sur TV, les marges de sécurité et le focus D-pad sont conservés. La rotation est permise sur téléphone.

## Enregistrements locaux

Une seule capture à la fois; les programmations qui se chevauchent sont refusées. La lecture fournisseur est arrêtée quand une capture démarre, pour éviter de consommer une deuxième connexion. La lecture des fichiers terminés ou arrêtés est locale. Les émissions en cours de capture deviennent lisibles après leur fin ou leur arrêt.

L’appareil doit rester allumé et connecté. Android peut demander les notifications et l’autorisation **Alarmes et rappels**. Une permission refusée n’est jamais présentée comme une programmation réussie. Après un redémarrage, les programmations futures sont rétablies; une capture interrompue reste explicitement incomplète. Une application forcée à l’arrêt doit être rouverte pour rétablir ses alarmes.

Durée maximale d’une capture : 4 heures. Sur Android 15 et plus, les services de téléchargement en arrière-plan disposent d’un quota système de 6 heures par période de 24 heures; Pink TV conserve le fichier partiel et affiche la limite si Android arrête le service. [Documentation Android](https://developer.android.com/develop/background-work/services/fgs/timeout).

Le débit du fournisseur détermine l’espace occupé. La capture s’arrête avant de saturer le stockage. Les fichiers, favoris et comptes ne sont pas synchronisés entre appareils. La désinstallation supprime les données locales. Changer de compte demande d’abord d’annuler les captures programmées ou en cours.

## Développement

JDK 17 ou 21, SDK Android 35, Gradle Wrapper inclus.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Pour une version signée, configurer `PINKTV_KEYSTORE`, `PINKTV_STORE_PASSWORD`, `PINKTV_KEY_PASSWORD`, alias `pinktv`, ou un fichier ignoré `local-signing/signing.properties` contenant `storeFile`, `storePassword`, `keyPassword`. Conserver la clé originale pour les prochaines mises à jour. Ne jamais la publier.

```powershell
.\gradlew.bat :app:assembleRelease
```

Le décodeur stéréo local optionnel est fourni pour ARM 32/64 bits et x86/x86_64. Sources, provenance et licences dans `third_party/stereo_decoder`, notices dans l’application. Les vérifications et leurs limites sont consignées dans [docs/VALIDATION.md](docs/VALIDATION.md).
