# R Music — شجرة الفصول (برونت الصور + المقاسات)

الفكرة: شجرة ساكورا/برقوق **واحدة** في نص الشاشة، تحت غلاف الصورة، بتتغير حسب الفصل. الكود هيركّب الطبقات فوق بعض، فكل حاجة لازم تتولّد **طبقة لوحدها بخلفية شفافة**.

## 0) قواعد ثابتة لكل الصور (حطها في أول كل برونت)
- Style: match the reference image exactly (soft realistic painterly look, dense clustered blossoms, no outline), no text, no watermark, no frame.
- Reference: the attached sakura tree image (twisted thick brown trunk leaning slightly right, wide asymmetrical canopy that drops low on both sides, small tuft of grass and petals at the base). Attach it to EVERY prompt as the reference image and say: keep this exact trunk shape, branch layout, canopy silhtechniques and proportions; only change the season state.
- Camera: straight side-on, orthographic, NO perspective, NO 3/4 angle, eye-level. Same camera for every image.
- Background: fully transparent PNG (if the tool can't, solid pure green #00FF00 to key out).
- Light: soft daylight from top-left, same in every image. No cast shadow on the background.
- The tree trunk, branch structure, position and size must be IDENTICAL in every season image (only leaves / flowers / snow / colour change). Generate season 1 first, then use it as the reference image for the rest.

> مهم: الصورة اللي بعتّها مقاسها صغير (حوالي 360 بكسل) وخلفيتها بيضا. اطلب من الأداة تطلع الصور 768×768 بخلفية شفافة، ولو مش بتدعم الشفافية اعمل الخلفية أخضر #00FF00 وأنا أشيلها.

## 1) المقاسات (PNG شفاف)
| الطبقة | المقاس للفريم | الفريمات | الملف |
|---|---|---|---|
| الشجرة | 768 × 768 | حسب الجدول تحت | `tree_<season>_<state>.png` |
| الأرض | 1080 × 240 (عرض الشاشة) | حسب الجدول تحت | `ground_<season>_<state>.png` |
| جزيئات سقوط (بتلات/ورق/تلج) | 64 × 64 لكل فريم | 8 فريم في صف واحد = 512 × 64 | `fx_<type>.png` |
| كرة القش (Tumbleweed) | 128 × 128 لكل فريم | 8 فريم = 1024 × 128 | `fx_tumbleweed.png` |

- الشجرة: شجرتك عريضة أكتر من طولها (تاج واسع بينزل على الجنبين)، فالتاج يملأ 90% من العرض والارتفاع حوالي 80%، والجذع في نص الصورة بالظبط، قاعدة الجذع تلمس الحافة التحتية (مسافة 0). التاج (الأوراق) يملأ تقريباً 85% من العرض.
- الأرض: خط الأرض الفعلي عند ارتفاع 60% من الصورة من فوق (فوقه 40% شفاف تقعد عليه الشجرة).
- الفريمات في الشيت: بدون مسافات بينها، كل فريم في مربع ثابت.
- الزوايا: كل الصور زاوية صفر (جانبية مستقيمة). الدوران بس في الجزيئات وكرة القش (تفصيل تحت).

## 2) الربيع — تتفتح وتوقّع بتلات
الشجرة (3 حالات، نفس الجذع):
- `tree_spring_1` براعم: أغصان فيها براعم صغيرة وردي غامق، ورق ما فتحش، حوالي 25% كثافة.
- `tree_spring_2` تفتّح نص: زهور وردي فاتح نصها مفتوح، 60% كثافة.
- `tree_spring_3` تفتّح كامل: تاج ممتلئ زهور وردي فاتح وأبيض 100% كثافة.

الأرض (2 حالة): `ground_spring_1` عشب أخضر فاتح نظيف، `ground_spring_2` عشب + بتلات وردي متناثرة على الأرض.

الجزيئات `fx_petal`: 8 فريم، بتلة ساكورا واحدة، كل فريم مدور 45° عن اللي قبله (0°، 45°، … 315°) وبميلان بسيط عشان تبان بتتقلّب. الكود هو اللي بيحركها ويوقّعها.

Prompt (الشجرة، حالة 3):
> Realistic soft painterly art, same style as the reference of a single cherry blossom (sakura) tree in full bloom, side-on view, thick dark-brown trunk with gnarled branches, dense canopy of pale pink and white blossoms, centred, base of trunk touching the bottom edge, transparent background, 768x768, [القواعد الثابتة].

Prompt (البتلة):
> Sprite sheet 512x64, 8 frames of one pink sakura petal, each frame rotated 45 degrees more than the previous, slight tilt for a tumbling look, transparent background, no gaps between frames.

## 3) الصيف — جفاف + صحرا
الشجرة (2 حالة):
- `tree_summer_1` قليلة الورق: ورق أخضر مصفر متفرق حوالي 20%، الأغصان باينة.
- `tree_summer_2` ناشفة: أغصان عارية جافة، مفيش زهور، ورقتين ثلاثة مصفرين بس.

الأرض (2 حالة): `ground_summer_1` رمل أصفر مع شقوق جفاف خفيفة، `ground_summer_2` صحرا: رمل وكثبان صغيرة وحجارة وعشب أصفر ميت.

كرة القش `fx_tumbleweed`: 128×128، 8 فريم، تلف 45° كل فريم (دورة كاملة 360°)، قش بني فاتح متشابك على شكل كرة. الكود بيحركها أفقياً على الأرض تحت الشجرة (من الشمال لليمين أو العكس) وبتلف.

Prompt:
> Realistic soft painterly art, same style as the reference of the same sakura tree in drought, nearly bare dry branches with a few yellowed leaves, side-on, centred, transparent background, 768x768, [القواعد الثابتة].

Prompt (القش):
> Sprite sheet 1024x128, 8 frames of a rolling tumbleweed (dry tangled straw ball), each frame rotated 45 degrees more than the previous so the loop is seamless, transparent background.

## 4) الخريف — الورق يقلّ بالتدريج لحد ما يخلص
الشجرة (5 حالات، نفس الجذع، الألوان برتقالي/أحمر/ذهبي):
- `tree_autumn_1` 100% ورق (بداية الخريف)
- `tree_autumn_2` 75%
- `tree_autumn_3` 50%
- `tree_autumn_4` 25%
- `tree_autumn_5` 0% — الأغصان عريانة تماماً (نهاية الخريف)

الأرض (5 حالات، كل واحدة بتقابل الشجرة): `ground_autumn_1` أرض نظيفة، `_2` شوية ورق، `_3` فرشة ورق، `_4` كومة أكبر، `_5` كومة ورق كبيرة متجمعة تحت الشجرة (كل الورق اللي وقع).

الجزيئات `fx_leaf`: 8 فريم، ورقة واحدة برتقالي/أحمر، بتدور 45° كل فريم.

الكود: رقم الحالة بيتحدد من تاريخ اليوم داخل الموسم (يعني الخريف مقسوم 5 أجزاء)، فالتدرّج بيحصل لوحده من يوم ليوم.

Prompt (الحالة 3):
> Realistic soft painterly art, same style as the reference of the same sakura tree in autumn, half of the leaves gone, remaining leaves orange, red and gold, branches visible, side-on, centred, transparent background, 768x768, [القواعد الثابتة].

## 5) الشتا — تلج
الشجرة (3 حالات):
- `tree_winter_1` أغصان عريانة وتلج خفيف على الأغصان من فوق.
- `tree_winter_2` تلج متوسط متراكم على كل الأغصان وجليد صغير متدلّي.
- `tree_winter_3` تلج كتير متراكم سميك على الأغصان والجذع.

الأرض (3 حالات): `ground_winter_1` تلج رقيق، `_2` تلج متوسط، `_3` كومة تلج عالية حوالين الجذع (التلج يتكوّم عليها).

الجزيئات `fx_snow`: 8 فريم، ندفة تلج واحدة 6 أطراف، 45° بين كل فريم. اعمل منها 3 أحجام (16 / 32 / 64 بكسل جوه نفس مربع 64×64) والكود هو اللي بينزلها من فوق.

Prompt (الحالة 3):
> Realistic soft painterly art, same style as the reference of the same sakura tree in deep winter, bare branches heavily covered with thick snow, small icicles, snow piled around the trunk base, side-on, centred, transparent background, 768x768, [القواعد الثابتة].

## 6) المطر — طبقة طقس فوق أي فصل
المطر مش فصل لوحده، هو طبقة بتتركّب فوق الشجرة والأرض. الكود يختار: الربيع والخريف مطر عادي، الصيف مطر نادر خفيف (والأرض الجافة تبان مبلولة)، والشتا تلج بدل المطر (مفيش مطر في الشتا).

| الملف | المقاس | الفريمات | الوصف |
|---|---|---|---|
| `fx_rain_far.png` | 512 × 512 | 1 (تتكرر رأسياً بدون خط واصل) | خطوط مطر رفيعة باهتة، بعيدة |
| `fx_rain_near.png` | 512 × 512 | 1 (تتكرر رأسياً) | خطوط مطر أسمك وأوضح، قريبة |
| `fx_splash.png` | 384 × 32 | 6 فريم (64 × 32 للفريم) | رشة نقطة بتخبط في الأرض |
| `fx_ripple.png` | 768 × 32 | 6 فريم (128 × 32 للفريم) | دايرة موج بتوسع على البرك |
| `ground_puddles.png` | 1080 × 240 | 1 | برك مية شفافة تتحط فوق أي أرض |

- الزاوية: خطوط المطر مايلة 10° لليمين من الرأسي، نفس الزاوية في الطبقتين.
- حركة المطر: الكود بيسحب الصورتين لتحت بسرعتين مختلفتين (القريب أسرع) فلازم الحافة العلوية تلتحم مع السفلية بالظبط.
- الرشة والموج: فريمات متتالية من الصغير للكبير ثم تختفي، وسط الفريم هو نقطة الخبطة.
- التظليل (غيم ولون رمادي على الشجرة) الكود بيعمله بفلتر، مش محتاج صور.

Prompt (طبقة المطر القريب):
> Seamless vertically tiling rain layer, 512x512, thin light-blue-white streaks tilted 10 degrees to the right, same style as the reference, transparent background, top and bottom edges match exactly, no splashes, no clouds.

Prompt (الرشة):
> Sprite sheet 384x32, 6 frames of a raindrop splash on the ground growing from a tiny dot to a small crown then fading, side-on view, same style as the reference, transparent background, no gaps between frames.

Prompt (الموج):
> Sprite sheet 768x32, 6 frames of a puddle ripple, a thin elliptical ring expanding and fading, top-down tilted, same style as the reference, transparent background.

Prompt (البرك):
> Transparent overlay 1080x240 of scattered shallow puddles reflecting a soft grey sky, same style as the reference, to be placed on any ground layer, nothing else in the image.

## 7) إجمالي الملفات
- شجرة: 3 + 2 + 5 + 3 = **13** صورة
- أرض: 2 + 2 + 5 + 3 = **12** صورة
- جزيئات: بتلة، ورقة، ندفة = **3** شيتات (8 فريم) + كرة القش **1** شيت (8 فريم)
- مطر: **5** ملفات (قريب، بعيد، رشة، موج، برك)
- المجموع: **34** ملف. ابدأ بـ `tree_spring_3` (المرجع)، وبعده كل الباقي بيها كصورة مرجعية.

## 8) نسمّي ونسلّم
- أسماء ملفات بحروف صغيرة وشرطة سفلية بالظبط زي اللي فوق (`tree_autumn_3.png`).
- ابعتهم لي كلهم (أو zip) وأنا أركّبهم في الكود: طبقة أرض، فوقها الشجرة، فوقهم الجزيئات، وكلهم تحت غلاف الأغنية في نص الشاشة.
