# R Music — البرونتات المتبقية (انسخ كل واحد زي ما هو)

## ازاي تستخدمها
- ارفق مع كل برونت الصورة المرجعية: `tree_spring_3` (الربيع الكامل). للأرض ارفق برضه نفس الصورة عشان الستايل.
- كل طلب = صورة واحدة بس. خلفية خضرا `#00FF00` نضيفة، أنا أشيلها وأظبط المقاس.
- ابدأ كل برونت بالبلوك ده (اسمه BASE):

BASE: Realistic soft painterly art, exactly the same style, colours and brush texture as the attached reference image. Side-on view, orthographic, no perspective, soft daylight from top-left, no text, no watermark, no frame, no cast shadow. Solid pure green #00FF00 background, perfectly flat, nothing else in the image.

- الشجرة: حط بعد BASE دايماً: `Keep the exact same twisted trunk, branch layout, grass-and-moss base, canopy outline and size as the reference. Change ONLY the season state below. One tree only, centred.`

## أنا هعملها بالكود (متطلبش منك صور)
المطر كله (خطوط قريب وبعيد، رشة، موج، برك) وتدوير الجزيئات (8 فريم لكل بتلة وورقة وندفة وكرة القش). أنت تبعتلي **صورة واحدة** لكل عنصر وأنا أعمل الشيتات.

---
## A) الأشجار (9 صور)

### tree_summer_2 — ناشفة
[BASE] [Tree rule] Season state: severe drought. Completely bare dry grey-brown branches, no flowers, no buds, only two or three tiny yellowed dry leaves hanging. Grass at the base is dry and yellow.

### tree_autumn_1 — 100%
[BASE] [Tree rule] Season state: early autumn, full dense canopy of orange, red and gold leaves, no bare spots, same canopy volume as the full bloom reference.

### tree_autumn_2 — 75%
[BASE] [Tree rule] Season state: autumn, about 75% of the leaves remaining in orange, red and gold, small gaps showing branches, a few leaves drifting off.

### tree_autumn_3 — 50%
[BASE] [Tree rule] Season state: autumn, about 50% of the leaves remaining in orange, red and gold, branches clearly visible through the canopy.

### tree_autumn_4 — 25%
[BASE] [Tree rule] Season state: late autumn, only about 25% of the leaves remaining, mostly gold and brown, scattered on the tips of the branches.

### tree_autumn_5 — 0%
[BASE] [Tree rule] Season state: end of autumn, completely bare branches, no leaves at all on the tree. Dry orange leaves lie in a small heap around the base.

### tree_winter_1 — تلج خفيف
[BASE] [Tree rule] Season state: early winter, bare branches with a thin dusting of snow on top of the branches, a little snow at the base.

### tree_winter_2 — تلج متوسط
[BASE] [Tree rule] Season state: winter, bare branches with medium snow accumulated on all branches, small icicles hanging, snow around the base of the trunk.

### tree_winter_3 — تلج كتير
[BASE] [Tree rule] Season state: deep winter, bare branches heavily covered with thick snow, icicles, trunk partly covered with snow, a high snow mound around the base.

---
## B) الأرض (12 صورة) — شريط عريض
لو الأداة مش بتدعم شريط عريض، اطلب أعرض مقاس ممكن (مثلاً 1536×512) وأنا أقصّ.
قاعدة الأرض بعد BASE: `Wide ground strip only, no tree, no sky. The ground surface runs horizontally across the middle of the image with soft uneven edges fading to the green background at both ends. Empty space above the ground.`

- ground_spring_1: [BASE] [Ground rule] Fresh light-green grass with tiny wildflowers, clean.
- ground_spring_2: [BASE] [Ground rule] Fresh light-green grass with many pink cherry petals scattered across it.
- ground_summer_1: [BASE] [Ground rule] Dry yellow sandy soil with fine drought cracks and a few dry grass tufts.
- ground_summer_2: [BASE] [Ground rule] Desert sand with small low dunes, a few stones and dead yellow grass.
- ground_autumn_1: [BASE] [Ground rule] Mossy grass, clean, no leaves.
- ground_autumn_2: [BASE] [Ground rule] Mossy grass with a few orange and red fallen leaves scattered.
- ground_autumn_3: [BASE] [Ground rule] Grass covered by a layer of orange, red and gold fallen leaves, about half covered.
- ground_autumn_4: [BASE] [Ground rule] Ground mostly covered by fallen leaves, a bigger leaf pile in the middle.
- ground_autumn_5: [BASE] [Ground rule] Ground fully covered by fallen autumn leaves with a large heap of leaves gathered in the middle.
- ground_winter_1: [BASE] [Ground rule] Thin layer of snow over grass, grass tips showing.
- ground_winter_2: [BASE] [Ground rule] Medium snow cover, smooth, no grass showing.
- ground_winter_3: [BASE] [Ground rule] Deep snow with a high soft mound in the middle.

---
## C) عناصر مفردة (4 صور) — صورة واحدة لكل عنصر
- fx_petal: [BASE] One single pink cherry blossom petal, large, centred, filling most of the image, fully visible, nothing else.
- fx_leaf: [BASE] One single autumn maple leaf in orange-red, large, centred, filling most of the image, nothing else.
- fx_snow: [BASE] One single six-pointed snowflake, white with light blue edges, large, centred, filling most of the image, nothing else.
- fx_tumbleweed: [BASE] One single dry tumbleweed, a round ball of tangled light-brown straw, centred, filling most of the image, nothing else.

---
## العدد
9 أشجار + 12 أرض + 4 عناصر = **25 صورة**. اللي وصلني قبل كده 4 أشجار (ربيع 1 و2 و3 + صيف 1).
