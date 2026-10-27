# Validation de Pink TV 1.0.0

Vérifications réalisées pendant la préparation du 26 octobre 2026.

## Compilation et analyse

- APK universel de production signé avec une clé propre à Pink TV, paquet `ca.pinktv`, version 1.0.0 (1).
- Android SDK 35, minimum Android 8 (API 26), JDK 21 et Gradle 8.9.
- `:app:testDebugUnitTest` : 69 tests réussis.
- `:app:lintDebug` et contrôle de production : aucune erreur. Les avertissements restants concernent principalement les versions de dépendances héritées, les textes français intégrés au code et la mesure conservatrice de l’espace libre.
- Signature APK v2 vérifiée avec `apksigner`; certificat SHA-256 : `94f371ba32471161d1707172861c40a107885f441683acd7f43c34342c111273`.
- Recherche des noms personnels, des mots d’amour et des routes du serveur compagnon dans les sources publiées : aucun résultat.
- Comptes, anciennes données personnelles, historique Git du projet de départ et clé de signature exclus du nouveau dépôt et de l’APK.

## Tests sur Android 15 émulé

Les trois tests instrumentés utilisent un fournisseur HTTP factice dans le processus de test et un signal vidéo synthétique H.264/AAC. Ils n’utilisent aucun abonnement réel.

1. Connexion Xtream directe, paramètres URL encodés, catégories, chaînes, films, séries et détails d’épisodes. Compte chiffré avec Android Keystore, conservation des favoris à la réouverture et lors d’un changement de mot de passe, séparation des favoris entre deux utilisateurs du même serveur. Chargement du décodeur natif stéréo et lecture directe jusqu’à l’état READY.
2. Parcours tactile de l’accueil au catalogue Séries, grille de deux colonnes sur téléphone, fiche avec saisons et épisodes, puis guide TV. Contrôle visuel de captures en portrait. Disposition en paysage examinée séparément.
3. Capture locale complète avec l’activité en arrière-plan, lecture du fichier MPEG-TS local jusqu’à READY, annulation avec conservation des octets reçus, suppression, fin prématurée du flux marquée incomplète et enregistrement démarré par une alarme exacte puis terminé à l’heure prévue.

Les tests unitaires couvrent aussi les conflits d’horaires, l’état des téléchargements interrompus, la validation des URL, les colonnes adaptatives et les règles conservées du catalogue, du guide, de la reprise et de l’audio.

## Limites de cette validation

Pas de connexion à un fournisseur commercial réel, pas d’essai sur Firestick physique ni sur un téléphone physique. Le rendu final des codecs, du son multicanal, des télécommandes et des politiques de veille propres aux constructeurs reste à vérifier sur ces appareils. Les longues captures et le quota Android de 6 heures ne sont pas testés en temps réel; le service gère explicitement le rappel système de dépassement.

La disposition TV garde le focus, les marges et le guide d’origine; les tests instrumentés ci-dessus sont réalisés dans l’émulateur Android, et ne constituent pas une certification Fire TV.

## Reproduire les tests instrumentés

Sur un appareil ou émulateur de test, autoriser les notifications et les alarmes pour `ca.pinktv`, puis exécuter :

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Les identifiants `test` / `dummy` du fournisseur factice ne sont pas des identifiants d’abonnement. Les captures produites par le test sont enregistrées dans le répertoire `qa` des fichiers externes privés de l’application de test.
