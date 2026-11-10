# Spécification — Plugin Rider « Azure Service Fabric » (V1)

| | |
|---|---|
| Statut | Brouillon v1.1 — à valider (intègre la review du 2026-11-09) |
| Date | 2026-11-09 |
| Cible | JetBrains Rider 2026.2.x (Windows) |
| Projets cibles | Applications Service Fabric `.sfproj` (format VS2022 / VS2026) |

---

## 1. Contexte et objectif

Rider n'offre aucune fonctionnalité Service Fabric (SF) (demande JetBrains [RIDER-10370](https://youtrack.jetbrains.com/issue/RIDER-10370), ouverte depuis 2017, ~200 votes, « pas de plan »). Les développeurs doivent repasser par Visual Studio pour packager, publier et déboguer.

**Objectif de la V1 :** permettre de **packager** et **publier** une application SF depuis Rider, et d'éditer confortablement ses **manifests XML**, sans Visual Studio.

**Principe :** le plugin **ne touche pas au modèle de projet**. Rider charge et reconnaît déjà les `.sfproj` (voir §2) ; le plugin ajoute seulement des actions et des configurations d'exécution à ces projets.

## 2. Constats établis

Le plugin s'appuie sur deux constats clés : la commande Build de Rider ne produit pas de package (C4), mais la cible MSBuild `Package` fonctionne sans les outils SF de Visual Studio (C5). Le détail des preuves figure en [annexe A](#annexe-a--constats-et-preuves).

## 3. Périmètre

### 3.1 Inclus en V1

| ID | Fonctionnalité |
|---|---|
| F1 | Détection des projets SF de la solution |
| F2 | Action **Package** (menu contextuel du projet) |
| F3 | Action **Publish…** avec choix du profil de publication |
| F4 | Configuration d'exécution **« Service Fabric Application »** (Package + Deploy) |
| F5 | Validation et complétion XML des manifests via les XSD du SDK |
| F6 | Diagnostics d'environnement (SDK SF, module PowerShell, cluster local) |

### 3.2 Exclu de la V1 (V2+)

- **Débogage** (attachement automatique aux processus des services). Piste : après déploiement, retrouver les PID des `CodePackage` (via `Get-ServiceFabricDeployedCodePackage` ou par nom d'exécutable) et attacher le débogueur .NET de Rider ⇒ probablement besoin d'un composant backend (C#) et d'un modèle `rd`.
- **Mise à jour automatique** de `ApplicationManifest.xml` / `ApplicationParameters` (équivalent VS de `UpdateServiceFabricApplicationManifest`). Piste : réimplémenter `_UpdateServiceFabricApplicationManifest` ou appeler `AppManifestCleanupUtil.exe` du SDK.
- Détection par flavor backend (`ServiceFabricProjectFlavor`), si l'extension ne suffit pas.
- Assistant « Ajouter un service Service Fabric », templates de projets.
- Gestion du cluster local (start/stop/reset), intégration Service Fabric Explorer.
- macOS / Linux.

## 4. Exigences fonctionnelles

### F1 — Détection

- En V1, un projet est « SF » si son fichier a l'extension `.sfproj` (solutions `.sln` et `.slnx`).
- Les actions F2/F3 ne sont visibles **que** sur ces projets.
- Les projets SF chargés affichent une icône Service Fabric dans l'explorateur de solution (icône du Local Cluster Manager lue à l'exécution depuis le SDK installé, rien n'est redistribué ; icône maison en secours).
- Si le projet est déchargé, les actions sont visibles mais désactivées, avec une infobulle « Projet déchargé ».

### F2 — Package

- Menu contextuel du projet SF : **Service Fabric → Package**.
- Exécute toujours le MSBuild du SDK .NET (constats C5, C7), quel que soit le toolset configuré dans Rider :
  `dotnet msbuild "<sfproj>" /t:Package /p:Configuration=<cfg> /p:Platform=<plat> /p:ApplicationProjectTargetsPath=`
  - `<cfg>` / `<plat>` = configuration et plateforme **du projet**, obtenues via la correspondance solution → projet du `.sln`/`.slnx` pour la configuration active. Si la correspondance est absente ou si la plateforme n'est pas définie dans le `.sfproj` : `x64`, avec un avertissement dans la console.
  - `ApplicationProjectTargetsPath` est vidé (`/p:ApplicationProjectTargetsPath=`) : la propriété globale remplace la valeur du `.sfproj`, et l'import conditionnel `Exists('')` est ignoré. Sous `dotnet msbuild`, la valeur par défaut pointe déjà dans le SDK .NET (`…\dotnet\sdk\<ver>\Microsoft\VisualStudio\v18.0\Service Fabric Tools\…`), où le fichier n'existe pas ; la surcharge est donc une garantie, pas une nécessité (voir C10).
  - SDK résolu par `dotnet` (respecte `global.json`). Si `dotnet` est introuvable : échec avec message explicite.
  - Chaque argument est passé séparément au processus (chemins avec espaces).
- Avant l'exécution, si le dossier des packages NuGet (`..\packages`) est absent : message invitant à restaurer les packages.
- Sortie diffusée en direct dans une console Rider. Les erreurs MSBuild sont cliquables (souhaitable, non bloquant pour la V1).
- Chemin du package : valeur de la propriété MSBuild `PackageLocation` ; à défaut, `<projet>\pkg\<cfg>`.
- Fin :
  - code retour 0 : notification de succès avec le chemin du package et un lien « Ouvrir le dossier » ;
  - code retour ≠ 0 ou annulation : notification d'échec, sans lien vers le dossier.

### F3 — Publish…

- Menu contextuel : **Service Fabric → Publish…** ouvre une boîte de dialogue :
  - **Profil** : liste des fichiers `PublishProfiles\*.xml` du projet. Par défaut : `Local.1Node.xml` s'il existe, sinon le premier profil par ordre alphabétique. Si aucun profil n'existe, le bouton OK est désactivé et le message « Aucun profil de publication » s'affiche.
  - **Configuration** : configuration de build (par défaut : configuration active).
  - **Upgrade** : liste *Selon le profil* (défaut) / *Forcer la mise à niveau* / *Interdire la mise à niveau*, transmise via `-OverrideUpgradeBehavior None | ForceUpgrade | VetoUpgrade`. *Selon le profil* applique l'élément `UpgradeDeployment` du profil.
  - **Overwrite behavior** : `Never` / `Always` / `SameAppTypeAndVersion`.
  - **Skip package validation** : case à cocher.
  - *Overwrite behavior* et *Skip package validation* ne s'appliquent qu'à une nouvelle installation : le script les ignore en mode mise à niveau. La boîte de dialogue les désactive quand *Forcer la mise à niveau* est choisi, ou quand *Selon le profil* avec `UpgradeDeployment Enabled="true"`.
  - **Paramètres additionnels** : champ libre ajouté à la fin de la ligne de commande (scripts personnalisés).
  - Un profil illisible ou mal formé n'est pas bloquant : avertissement dans la boîte de dialogue, valeurs par défaut appliquées.
- Pipeline :
  1. F2 (Package) avec la configuration choisie.
  2. **Seulement si** le Package renvoie 0 : exécution du script de déploiement avec l'interpréteur PowerShell configuré (voir ci-dessous) :
     `<powershell> -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "& { $ErrorActionPreference='Stop'; & '.\Scripts\Deploy-FabricApplication.ps1' -PublishProfileFile '<profil>' -ApplicationPackagePath '<pkg absolu>' -OverrideUpgradeBehavior <upgrade> [-OverwriteBehavior …] [-SkipPackageValidation] [<additionnels>]; exit $LASTEXITCODE }"`
- **Interpréteur PowerShell** : Windows PowerShell 5.1 (`powershell.exe`, 64 bits) par défaut ; PowerShell 7 (`pwsh.exe`) au choix dans les réglages du plugin. Le même interpréteur sert au diagnostic F6.
- `-ExecutionPolicy Bypass` est désactivable dans les réglages (pour respecter une politique imposée par GPO).
- Exécution dans le répertoire du projet SF, sans entrée interactive, sortie dans la console Rider.
- Si le profil vise un cluster sécurisé, les paramètres de connexion sont ceux du profil (`ClusterConnectionParameters`). Aucune saisie de secret dans le plugin en V1.
- Les options de la boîte de dialogue sont transmises uniquement par paramètres : le plugin n'écrit jamais dans les profils. Si un fichier temporaire est nécessaire, il est créé dans le dossier temp du système.
- Code retour ≠ 0, erreur PowerShell ou annulation ⇒ notification d'échec.

### F4 — Configuration d'exécution « Service Fabric Application »

- Type de *Run Configuration* disponible dans *Edit Configurations*.
- Création automatique : une configuration par projet SF détecté, **seulement si aucune n'existe déjà** pour ce projet. Une configuration supprimée par l'utilisateur n'est pas recréée. La création automatique est désactivable dans les réglages.
- Les configurations créées automatiquement sont stockées dans l'espace de travail local (non partagées). L'utilisateur peut cocher *Store as project file* pour les partager via VCS.
- Paramètres : projet SF, profil de publication, configuration, options de F3.
- **Run** = Package + Deploy (même pipeline que F3). Bouton **Debug** désactivé en V1.
- Avant le Run, la configuration est vérifiée ; une erreur claire s'affiche si le projet, le profil ou la configuration est introuvable, ou si le système n'est pas Windows.

### F5 — Manifests XML

- Associer `ServiceFabricServiceModel.xsd` (namespace `http://schemas.microsoft.com/2011/01/fabric`) aux fichiers XML dont l'élément racine utilise ce namespace **et** qui appartiennent à un projet SF ou à un dossier `PackageRoot` de service, notamment :
  - `ApplicationPackageRoot\ApplicationManifest.xml`
  - `PackageRoot\ServiceManifest.xml` (projets de services)
  - `PackageRoot\Config\Settings.xml`
  - `ApplicationParameters\*.xml`
  - `PublishProfiles\*.xml`
- Associer `StartupServicesModel.xsd` à `StartupServices.xml` s'il existe.
- Résultat attendu : complétion des éléments et attributs, et erreurs de validation dans l'éditeur.
- Le fournisseur de schémas vérifie lui-même la présence des fichiers XSD sur disque. S'ils sont absents (SDK non installé ou incomplet) : aucune association, aucune erreur bloquante (voir F6).

### F6 — Diagnostics d'environnement

Vérifications exécutées au premier usage d'une action SF dans la session de l'IDE. Les résultats sont mis en cache, sauf la vérification du cluster local, refaite à chaque Publish. L'action **Service Fabric → Re-check environment** vide le cache.

| Vérification | Méthode | En cas d'échec |
|---|---|---|
| SDK SF installé | Registre `HKLM\SOFTWARE\Microsoft\Service Fabric SDK\FabricSDKInstallPath` (vues 64 et 32 bits), puis présence de `schemas\ServiceFabricServiceModel.xsd` | Avertissement + lien de téléchargement ; F5 inactif |
| Module PowerShell SF | `Get-Module -ListAvailable ServiceFabric`, avec l'interpréteur configuré en F3 (timeout 30 s) | Publish bloqué avec message explicite |
| Script de déploiement | Présence de `Scripts\Deploy-FabricApplication.ps1` | Publish bloqué avec message explicite |
| Cluster local (si profil local) | Service Windows `FabricHostSvc` démarré **et** connexion TCP possible sur le point de terminaison du profil | Publish bloqué avec le message « Cluster local arrêté ou pas encore prêt » |

- Un profil est **local** si `ClusterConnectionParameters` est absent ou si son `ConnectionEndpoint` vise `localhost` ou `127.0.0.1`.
- Si une vérification ne peut pas aboutir (registre inaccessible, PowerShell introuvable, timeout), l'état est « inconnu » : avertissement, sans bloquer, et sans exception non gérée.

## 5. Exigences non fonctionnelles

- **Plateforme** : Windows uniquement. Le plugin s'installe sur toutes les plateformes, mais les actions SF sont masquées hors Windows.
- **Compatibilité** : Rider 2026.2.x (`since-build` = 262, `until-build` = `262.*`). Le Plugin Verifier est exécuté en CI sur chaque nouvelle version de Rider avant d'élargir `until-build`.
- **Aucune dépendance** à Visual Studio ni aux outils SF de VS.
- **Non intrusif** : aucune modification des fichiers `.sfproj`, `.sln`/`.slnx`, des manifests ni des profils de publication en V1.
- **Exécution asynchrone** : aucune action ne bloque l'UI.
- **Annulation** : le bouton Stop de la console arrête tout l'arbre de processus (MSBuild, PowerShell et leurs enfants). Après une annulation pendant le Deploy, la console signale que l'état du cluster peut être partiel.
- **Concurrence** : une seule exécution Package/Publish à la fois par projet ; l'action est désactivée pendant l'exécution.
- **Secrets** : le plugin ne journalise aucun secret qu'il manipule. La sortie du script est affichée telle quelle ; ne pas y écrire de secret relève du script.
- **Langue de l'UI** : anglais (cohérent avec Rider). Les messages sont externalisés dans un bundle de ressources ; aucune traduction n'est prévue en V1.

## 6. Architecture

### 6.1 Choix : plugin **frontend-only** (Kotlin) en V1

Toutes les fonctions V1 sont réalisables côté IntelliJ (frontend) : actions, run configurations, processus, XML. Le backend ReSharper (C#) n'est pas nécessaire en V1.

- Détection F1 : via le `WorkspaceModel` (entités projet Rider), en testant l'extension `.sfproj`.
- Squelette : [`rider-plugin-template`](https://github.com/JetBrains/rider-plugin-template) **sans** la partie .NET, ou IntelliJ Platform Gradle Plugin 2.x avec `rider()`.

### 6.2 Composants

| Composant | Point d'extension IntelliJ | Rôle |
|---|---|---|
| `SfProjectDetector` | service projet | Liste les projets SF de la solution (F1) |
| `SfActionGroup` (`Package`, `Publish`, `Re-check environment`) | `<actions>` dans `SolutionExplorerPopupMenu` | Menus F2, F3, F6 |
| `SfPackageRunner` | `GeneralCommandLine` + `KillableProcessHandler` + `ConsoleView` | Exécution MSBuild (F2) |
| `SfDeployRunner` | `GeneralCommandLine` + `KillableProcessHandler` + `ConsoleView` | Exécution PowerShell (F3) |
| `SfPublishDialog` | `DialogWrapper` | UI F3 |
| `SfRunConfigurationType` / `SfRunConfiguration` | `configurationType` | F4 |
| `SfSchemaProvider` | `xmlSchemaProvider` (ou `standardResourceProvider`) | F5 |
| `SfEnvironmentChecker` | service application | F6 |
| `SfSettings` | `applicationConfigurable` | Interpréteur PowerShell, `Bypass`, création auto F4 |

## 7. Critères d'acceptation (V1)

Sur le dépôt `common.auth` (solution `vesact.common.auth.sln`), sauf mention contraire :

1. **CA1** — Le menu *Service Fabric* apparaît sur `vesact.auth.sf` et **pas** sur les projets C#, y compris avec la solution au format `.slnx`.
2. **CA2** — *Package* produit `vesact.auth.sf\pkg\Debug\` contenant `ApplicationManifest.xml` et `vesact.auth.svc\{Code, ServiceManifest.xml}` ; la console affiche la sortie MSBuild. Le résultat est identique sur une machine avec et sans Visual Studio installé.
3. **CA3** — *Publish…* avec `Local.1Node.xml`, cluster local démarré : l'application est déployée (visible dans Service Fabric Explorer) ; code retour 0 ; notification de succès. Vérifié avec PowerShell 5.1 et avec `pwsh`.
4. **CA4** — *Publish…* avec le cluster local arrêté : Publish bloqué avec le message « Cluster local arrêté ou pas encore prêt », sans exception non gérée.
5. **CA5** — La run configuration créée automatiquement pour `vesact.auth.sf` produit les résultats de CA2 et CA3 via le bouton Run, et n'est pas ajoutée aux fichiers partagés de `.idea`.
6. **CA6** — Dans `ApplicationManifest.xml`, la complétion propose les éléments du schéma (`ServiceManifestImport`, `DefaultServices`…) et une balise invalide est soulignée.
7. **CA7** — Sur une machine sans SDK SF : le plugin se charge, F6 affiche un avertissement, aucune erreur dans *IDE Internal Errors*.
8. **CA8** — Aucun fichier du dépôt n'est modifié par le plugin (hors `pkg\`, `bin\`, `obj\` et les fichiers non partagés de `.idea\`).
9. **CA9** — Un Package volontairement en échec (erreur de compilation) affiche une notification d'échec et n'enchaîne pas le Deploy.
10. **CA10** — Stop pendant le Deploy : aucun processus `powershell`/`pwsh` ou MSBuild ne reste actif.
11. **CA11** — Les options *Overwrite behavior*, *Skip package validation* et *Upgrade* apparaissent dans la ligne de commande affichée en console.
12. **CA12** — Sous macOS ou Linux, le plugin se charge et les actions SF sont masquées.

## 8. Risques

| Risque | Impact | Mitigation |
|---|---|---|
| API frontend Rider du modèle de projet peu documentée ou changeante | Détection F1 | Fallback : scan des `.sfproj` référencés par le `.sln`/`.slnx` ; Plugin Verifier en CI |
| Variantes de `Deploy-FabricApplication.ps1` (anciennes versions, scripts personnalisés) | F3 | N'utiliser que les paramètres communs ; champ « Paramètres additionnels » libre |
| Politique d'exécution PowerShell ou droits admin requis pour le cluster local | F3 | `-ExecutionPolicy Bypass` (désactivable) ; message explicite si accès refusé |
| Module `ServiceFabric` indisponible sous `pwsh` | F3 | 5.1 par défaut ; F6 vérifie le module avec l'interpréteur choisi |
| Compilation double x64/AnyCPU lors du Package (lenteur) | Confort | Documenter ; investiguer en V2 |
| Évolution du format `.sfproj` par Microsoft (extension VS2026) | Long terme | Ne dépendre que de la cible `Package` du package NuGet |

## 9. Questions ouvertes

1. Publication vers des clusters distants sécurisés (certificats) : à tester en V1 sur `cluster-test.example.com` ou à repousser ?

**Décisions prises (review du 2026-11-09) :** identifiant `ch.actemium.rider.servicefabric`, usage interne (pas de Marketplace en V1) ; MSBuild = toujours `dotnet msbuild` ; PowerShell 5.1 par défaut et `pwsh` au choix ; run configurations créées automatiquement mais non partagées.

---

## Annexe A — Constats et preuves

Investigations réalisées sur le poste de dev (Rider 2026.2, VS2026 18, SDK .NET 10.0.403, package `Microsoft.VisualStudio.Azure.Fabric.MSBuild` 1.7.9, projet `common.auth/vesact.auth.sf`).

| # | Constat | Preuve |
|---|---|---|
| C1 | Rider **charge déjà** le `.sfproj` comme projet MSBuild générique (Load/Unload, Build, NuGet). | Test manuel Rider ; `MsBuildProjectHost.IsApplicable => true` |
| C2 | Rider **identifie** le projet comme SF : `ServiceFabricProjectFlavor`, GUID `{A07B5EB6-E848-4116-A8D0-A826331D98C6}`, extension `.sfproj` (y compris en `.slnx`). | Décompilation `JetBrains.Platform.ProjectModel` / `Platform.Shell` |
| C3 | Rider **restaure automatiquement** `packages.config` (dossier `..\packages` recréé sur clone neuf). | Test manuel Rider |
| C4 | La commande Build de Rider sur le `.sfproj` réussit mais **ne produit pas** le package (`pkg\`) : la cible `Package` n'est jamais appelée. | Log de build Rider (0,8 s, aucune cible `Package` exécutée) |
| C5 | `dotnet msbuild <sfproj> /t:Package /p:Configuration=Debug /p:Platform=x64` produit un package valide **sans les outils SF de VS**. | Test CLI avec `/p:ApplicationProjectTargetsPath=` |
| C6 | Les targets VS (`Service Fabric Tools\…ApplicationProject.targets`) n'apportent que l'intégration CPS (Common Project System) de VS et la mise à jour automatique des manifests, activée seulement si `BuildingInsideVisualStudio=true`. | Lecture des targets |
| C7 | Rider utilise le MSBuild du SDK .NET (`dotnet\sdk\10.0.403\MSBuild.dll`). | Log de build Rider |
| C8 | Le publish VS repose sur `Scripts\Deploy-FabricApplication.ps1` (présent dans chaque projet) et un profil `PublishProfiles\*.xml`. | Fichiers du projet |
| C9 | Les schémas XML sont fournis par le SDK SF : `C:\Program Files\Microsoft SDKs\Service Fabric\schemas\ServiceFabricServiceModel.xsd` (+ `StartupServicesModel.xsd`). | Système de fichiers |
| C10 | Sous `dotnet msbuild`, `ApplicationProjectTargetsPath` vaut `C:\Program Files\dotnet\sdk\10.0.403\Microsoft\VisualStudio\v18.0\Service Fabric Tools\…targets` (fichier inexistant) ; `/p:ApplicationProjectTargetsPath=` le vide bien. `PackageLocation` = `pkg\Debug`. | `dotnet msbuild -getProperty` sur `vesact.auth.sf.sfproj` |
| C11 | `Deploy-FabricApplication.ps1` accepte `-OverrideUpgradeBehavior None\|ForceUpgrade\|VetoUpgrade` (défaut `None`), `-OverwriteBehavior`, `-SkipPackageValidation`, `-DeployOnly`, `-ApplicationParameter`, `-UseExistingClusterConnection`, `-SecurityToken`, `-CopyPackageTimeoutSec`. *Overwrite* et *Skip validation* ne sont utilisés qu'en installation neuve. Le script charge `ServiceFabricSDK.psm1` via la clé de registre `FabricSDKPSModulePath`. | Lecture du script de `vesact.auth.sf` |
