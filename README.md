# Live Translator — Android

Traduit **en direct** le texte de n'importe quelle app Android (WeChat, Taobao, 1688, Meituan, Alipay…).
La traduction s'affiche **pile par-dessus le texte d'origine**, et l'app reste 100 % utilisable : les touches passent au travers.

## Fonctionnement

```
App chinoise ──(API Accessibilité : texte + position exacte, sans OCR)──┐
                                                                        ▼
                         ScreenTranslatorService  (anti-rebond 350 ms, cache)
                                   │
              TranslationRouter ───┤ réseau OK + clé → DeepSeek / Qwen / Claude
                                   └ sinon / erreur → ML Kit hors-ligne (sur le téléphone)
                                   ▼
             OverlayView : calque plein écran NON touchable, une étiquette par texte
             Bulle « 译 » : afficher / masquer, déplaçable
```

| Fichier | Rôle |
|---|---|
| `ScreenTranslatorService.kt` | Lit l'écran, gère le calque et la bulle |
| `OverlayView.kt` | Dessine chaque traduction à la position de l'original, taille de texte auto |
| `TranslationRouter.kt` | Choix en ligne / hors-ligne, pause de 30 s après un échec |
| `LlmTranslator.kt` | DeepSeek / Qwen (format OpenAI) et Claude, lots + cache |
| `OfflineTranslator.kt` | ML Kit, détection de la langue par l'écriture |
| `MainActivity.kt` | Réglages : service, langue, clés, modèles hors-ligne |

## Obtenir l'APK sans rien installer (GitHub)

1. Crée un dépôt GitHub et pousse ce dossier :
   ```bash
   git init && git add . && git commit -m "Live Translator Android"
   git branch -M main
   git remote add origin https://github.com/TON_USER/live-translator-android.git
   git push -u origin main
   ```
2. Après environ 5 min : onglet **Releases** → **LiveTranslator.apk**. Ouvre ce lien **directement sur le téléphone** et installe (autorise « sources inconnues » pour le navigateur).
3. À chaque `git push`, un nouvel APK s'installe par-dessus l'ancien (même clé de signature, réglages conservés).

> En Chine, github.com peut être lent : télécharge avec le VPN actif.

## Ou compiler sur ton Mac (Intel OK)

Android Studio → Open → ce dossier → ▶︎. Pour installer sans fil sur le téléphone : Options pour les développeurs → **Débogage sans fil** → « Associer avec un code QR » (dans Android Studio : *Pair Devices Using Wi-Fi*).

## Réglages sur le téléphone

1. **Accessibilité** : Réglages → Accessibilité → Live Translator → Activer.
   - Grisé avec « paramètre restreint » (Android 13+) : Infos de l'app → ⋮ → **Autoriser les paramètres restreints**.
2. **Batterie** (Xiaomi, Huawei, Oppo, Vivo) : Infos de l'app → Batterie → **Aucune restriction**, et active le démarrage automatique. Sinon le système coupe le service.
3. **Clé DeepSeek** (platform.deepseek.com) ou **Qwen** (Alibaba Cloud Model Studio).
4. **Hors-ligne** : coche chinois, etc. → Télécharger, **avec le VPN**, une seule fois.

## Limites

- Les textes dessinés en image (jeux, certaines bannières, Flutter mal configuré) ne sont pas lisibles par l'accessibilité. Une V2 avec OCR (capture d'écran + ML Kit) est possible pour ces cas.
- Les champs de mot de passe et le texte en cours de saisie ne sont jamais lus.
- Pendant un défilement, les étiquettes disparaissent puis reviennent environ 0,4 s après l'arrêt.
# live-translator-apk
