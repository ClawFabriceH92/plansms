# Cahier des charges — App SMS programmés
### "Clone amélioré" de Auto Text – Schedule Messages (com.hnib.smslater)
*Projet Fabrice — établi le 17/08/2026*

---

## 1. Contexte & objectifs

Développer une application Android de **programmation de messages SMS** (envoi planifié, auto-réponse, envoi groupé), en s'inspirant d'**Auto Text – Schedule Messages** (Bizcraft Apps, 1M+ téléchargements, 4.3★) tout en corrigeant ses faiblesses documentées dans les avis :
- ❌ Fiabilité des envois planifiés (SMS non partis, "lucky if it sends 1 auto reply")
- ❌ Licence PRO par appareil (pas par compte)
- ❌ Support injoignable
- ❌ Dépendance à WhatsApp (fonctionne mal)

**Positionnement** : l'app **la plus fiable** de programmation SMS, 100% locale, avec règles intelligentes et confidentialité renforcée.

**Livraison** : GitHub Releases + auto-update (pattern Fabrice), sideload. Publication Play Store = option ultérieure (contraintes SMS).

---

## 2. Cible

- Fabrice (usage perso + démonstration) : rappels clients, messages périodiques, auto-réponse pro
- Utilisateurs professionnels : artisans, commerçants (confirmation RDV, relances)
- Utilisateurs sensibles à la confidentialité : pas de compte, pas de cloud

---

## 3. Fonctionnalités (MoSCoW)

### 3.1 CŒUR (v1) — MUST

**F1. SMS programmés**
- Créer un message : destinataire(s), texte, date + heure d'envoi
- One-shot ou **récurrent** : quotidien, hebdomadaire (jours précis), mensuel, jours ouvrés
- **Plage d'envoi** : ne pas envoyer entre 22h-7h → décaler au créneau suivant (configurable)
- Liste des programmations : statut (programmé / envoyé / échec / rattrapage), édition, duplication, suppression
- Historique complet des envois (recherche, filtre par statut)

**F2. Fiabilité & rattrapage (différenciateur n°1)**
- `WorkManager` + alarmes exactes (`setExactAndAllowWhileIdle`)
- **Rattrapage au démarrage** (`BOOT_COMPLETED`) : messages manqués pendant extinction → envoi automatique + notification
- **Journal de bord** : horodatage, statut, erreur, SIM, destinataire
- Confirmation d'envoi par notification (option)
- **Mode brouillon si échec** : le message n'est jamais perdu, reste modifiable
- Réveil robuste Doze/OEM (notification persistante optionnelle, wake lock court)

**F3. Modèles (templates)**
- CRUD de modèles avec **variables** : `{{prenom}}`, `{{nom}}`, `{{date}}`, `{{heure}}`
- Insertion rapide depuis l'écran de création
- Variables résolues à l'envoi (depuis le contact ou l'heure réelle)

**F4. Contacts**
- Sélecteur de contacts natif + numéros récents
- Multi-destinataires (envoi individuel à chacun, pas de groupe SMS)

**F5. Historique & statistiques**
- Journal des envois (échecs visibles en rouge avec raison)
- Compteurs : messages envoyés / jour, / mois, par contact

**F13. Statistiques d'appels & SMS (différenciateur)**
- **Par contact** : nombre d'appels émis / reçus / manqués, **durée totale de communication** (et par type), nombre de SMS envoyés / reçus
- **Vues** : liste par contact (tri : nb appels, durée, nb SMS), détail d'un contact (historique chronologique appels + SMS), vue globale (période : aujourd'hui, 7 jours, 30 jours, année)
- Graphiques simples (barres par jour/semaine) — style UI perso
- Données sources : `CallLog.Calls` + `Telephony.Sms` (Inbox/Sent) — lecture seule, rien n'est modifié
- Permissions : `READ_CALL_LOG`, `READ_SMS`, `READ_CONTACTS`
- ⚠️ Note confidentialité : ces données restent 100% locales ; écran "effacer les statistiques" (purge des données agrégées)

### 3.2 IMPORTANT (v2) — SHOULD

**F6. Auto-réponse**
- Activation globale + **règles** : **répondre à TOUS les correspondants SAUF liste noire** (ou UNIQUEMENT liste blanche)
- Texte de réponse configurable (+ variables {{prenom}}), délai avant réponse, anti-boucle (numéros courts/services ignorés, 1 réponse par expéditeur toutes les X min)
- Option "ne répondre que si téléphone inoccupé" (pas en appel)
- Nécessite le rôle **app SMS par défaut** OU service d'accessibilité (choix documenté + onboarding)

**F21. Groupes de contacts**
- Création de groupes : clients, famille, amis, travail…
- Sélection d'un groupe comme destinataire d'un message programmé → envoi à tous les membres
- Gestion dans un onglet dédié

**F7. Envoi groupé (bulk)**
- Sélection multiple de contacts
- Personnalisation `{{prenom}}` par destinataire
- **Délai entre envois** (anti-spam, ex. 3 s) + limite quotidienne configurable
- Rapport d'envoi (X envoyés / Y échecs)

### 3.3 NICE (v3) — COULD

**F8. Règles intelligentes**
- Conditions d'envoi : Wi-Fi connecté / en charge / plage horaire
- **Géofencing** : déclenchement à l'arrivée ou au départ d'un lieu ("je suis arrivé")
- Déclencheur **appel manqué** (envoyer un SMS après un appel manqué, avec délai)
- Batterie faible

**F9. Dual SIM**
- Choix de la SIM par défaut (réglage) ou par message (option)

**F10. Widget**
- Widget liste : prochains envois, bouton "nouveau message rapide"
- Raccourci de création

**F11. WhatsApp (semi-auto)**
- Programmer l'ouverture de WhatsApp avec message pré-rempli (envoi manuel final — limites Android)

**F14. WhatsApp Stats (via export/partage + notifications)**
- **Import d'un export officiel** : WhatsApp → Discussion → Exporter le chat (.txt) → Partager vers PlanSMS → analyse (messages par contact, par jour, heures de pointe, mots fréquents)
- **Notifications** (`NotificationListenerService`) : logger les nouveaux messages reçus (contact, heure, extrait) — optionnel, activable
- ⚠️ Limites claires : pas d'accès à l'historique complet (chiffrement E2E) ; export = la seule source complète ; accessibilité (lecture d'écran) = non retenue (fragile)

**F15. Suggestions IA (DeepSeek / Ollama / Serveur)**
- Générer ou reformuler un message : ton pro / amical / formel, longueur, langue
- Raccourci "✨ IA" dans l'éditeur de message et les templates
- **Désactivable à 100%** (réglage ON/OFF, désactivé par défaut, aucun appel réseau)
- **Réglages quand activée** :
  - Provider : **DeepSeek API** (clé API + modèle `deepseek-chat`/`deepseek-reasoner`) · **Ollama local** (URL + modèle, ex. `http://192.168.0.162:11434` + `qwen2.5:7b`) · **Serveur** (URL personnalisée)
  - Clé API ou URL + modèle renseignés par l'utilisateur (stockés localement, chiffrés si PIN actif)
- Aucune donnée envoyée sans action explicite de l'utilisateur

**F16. Agenda Outlook / Calendrier → SMS (flux complet)**
- **Accès** : popup de permission système `READ_CALENDAR` (demandée au 1er usage, révocable)
- **Lecture** : calendriers synchronisés sur le mobile (dont Outlook via CalendarProvider) — événements : titre, début/fin, lieu, participants (emails)
- **Mapping répertoire** : chaque participant (email Outlook) est associé au contact Android correspondant (match email → puis nom) → récupération du numéro
- **Proposition SMS** : à partir d'un RDV, l'app propose un message pré-rempli (confirmation, relance, retard) avec le contact mappé → **confirmation explicite avant envoi**
- Intégration au sélecteur de programmation (choix d'un événement pour lier un SMS programmé)

**F20. Amélioration du répertoire**
- Détection : participant avec email mais **aucun contact** mappé · contact existant **sans numéro** · doublons probables (même nom/email)
- **Proposition d'enrichissement** (avec confirmation, `WRITE_CONTACTS`) : créer la fiche / ajouter l'email ou le numéro / fusionner les doublons
- Journal des modifications proposées (annulable)

**F17. Relances client (modèle pro)**
- Envoi automatique de rappel à J-X : facture impayée, échéance, rendez-vous
- Basé sur les templates + variables ; flux guidé (créer une relance = choisir échéance → date d'envoi calculée)

**F18. Profils Pro / Perso**
- Deux profils séparés (messages, règles, PIN distincts), bascule rapide

**F19. Sauvegarde auto vers PC/Pi**
- Export JSON périodique (hebdo) vers un dossier local ou partage réseau (WebDAV/SMB simple) — option

### 3.4 CONFIDENTIALITÉ (transverse, dès v1) — MUST

**F12. Sécurité**
- **Verrouillage PIN 4 chiffres / biométrie** au lancement (défaut : désactivé)
- **Chiffrement AES-256** (Android Keystore) des messages marqués sensibles + de l'export
- **Export/Import JSON** complet (programmations + templates + règles)
- 100% local, zéro compte, zéro réseau (hors envoi SMS lui-même)

---

## 4. Règles métier

| Règle | Comportement |
|---|---|
| Heure passée (one-shot) | Désactiver la programmation, statut "expirée" (pas d'envoi) |
| Téléphone éteint à l'heure H | Rattrapage au démarrage → envoi + notification |
| Récurrent + plage d'envoi | Si H hors plage → décaler au créneau suivant le même jour ou lendemain |
| Échec d'envoi (SMS) | 2 tentatives espacées de 60 s, puis statut "échec" + brouillon conservé |
| Auto-réponse | 1 réponse max par conversation / 24 h (anti-boucle), délai par défaut 2 min |
| Bulk | Délai inter-envois 3 s, limite quotidienne 100 (configurable) |
| Variables non résolues | Remplacer par vide ou garder `{{var}}` (option) |

---

## 5. Architecture technique

**Stack**
- Kotlin · Jetpack Compose (UI thème perso, pas de Material par défaut)
- Room (programmations, templates, journal, règles)
- WorkManager (scheduling fiable) + AlarmManager exact (réveil précis)
- BroadcastReceiver : BOOT_COMPLETED, SMS_RECEIVED (auto-réponse), PHONE_STATE (appel manqué)
- Hilt (injection), DataStore (préférences)
- Min SDK 26 · target 35
- Keystore Android pour le chiffrement

**Permissions**
- `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS` (auto-réponse), `READ_CONTACTS`, `WRITE_CONTACTS` (enrichissement répertoire, avec confirmation)
- `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`
- `ACCESS_FINE_LOCATION` (géofencing v3), `READ_PHONE_STATE` (SIM), `READ_CALL_LOG` (stats v0.5), `READ_CALENDAR` (agenda v0.3)

**Modules**
```
app/ (UI Compose, navigation)
core/ (domaine, use cases)
data/ (Room, repositories)
sms/ (envoi, réception, journal)
scheduler/ (WorkManager, alarmes, rattrapage)
security/ (PIN, Keystore, chiffrement, export JSON)
```

---

## 6. UI/UX

- **Écrans** : Accueil (prochains envois), Création/édition, Modèles, Auto-réponse (règles), Journal, Réglages
- **Thème** : mode clair/sombre, dégradés, badges de statut, coins arrondis (style Fabrice)
- **Figma-like** : aperçu du message avec variables résolues
- Onboarding : permissions expliquées simplement (pourquoi chaque permission)
- Notifications : envoi confirmé / échec / rattrapage (actions : voir, réessayer)

---

## 7. Monétisation & distribution

- **v1-v3** : gratuite, livrée GitHub Releases (zip APK + auto-update)
- Option Play Store (plus tard) : freemium PRO (règles illimitées, thèmes, pas de pub) — nécessite rôle app SMS par défaut
- Pas de pub dans la version perso

---

## 8. Tests & validation

- Unit tests : règles métier (plages, récurrences, variables, anti-boucle)
- Tests instrumentés : envoi réel sur appareil (émulateur + téléphone), Doze, redémarrage
- Checklist manuelle : programmation → extinction → rallumage → rattrapage
- Test auto-réponse avec 2 numéros réels

---

## 9. Risques & limites

| Risque | Mitigation |
|---|---|
| Play Store restreint les apps SMS | Sideload GitHub ; si Play : rôle app SMS par défaut + politique respectée |
| OEM tue les alarmes (Xiaomi, Huawei...) | WorkManager + notification persistante optionnelle + guide réglages batterie |
| Auto-réponse sans rôle SMS par défaut | Onboarding clair, fallback accessibilité |
| Confidentialité SMS sensibles | Chiffrement optionnel, pas de logs texte brut (sauf choix) |

---

## 10. Roadmap

- **v0.1** : cœur (F1-F5, F12) — scheduling fiable + templates + journal + PIN
- **v0.2** : auto-réponse + envoi groupé + **suggestions IA** + **relances client** (F6-F7, F15, F17)
- **v0.3** : règles intelligentes + widget + dual SIM + **agenda→SMS (F16) + enrichissement répertoire (F20)** + profils (F8-F10, F16, F18, F20)
- **v0.4** : WhatsApp semi-auto + **WhatsApp Stats** + sauvegarde auto (F11, F14, F19)
- **v0.5** : **statistiques appels & SMS** + coûts opérateur + Tasker (F13, F5+)

---

*Décisions provisoires : nom = **PlanSMS** (modifiable) · gratuit, pas de pub · UI en français · min SDK 26 (Android 8+) · cible 35.*
