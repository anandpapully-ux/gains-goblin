#!/usr/bin/env python3
"""
Builds app/src/main/assets/food_db.json, the offline food database searched by Add food.

Sources (download them once, they are not kept in git):

1. Indian Nutrient Databank (INDB): 1,014 cooked Indian recipes, values per 100 g and per serving.
   Vijayakumar A, Dubasi HB, Awasthi A, Jaacks LM. Development of an Indian Food Composition Database.
   Current Developments in Nutrition 2024;8(7):103790. doi:10.1016/j.cdnut.2024.103790 (CC BY 4.0).
   https://github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB- at fbdb62bec519c6e2468582a799fb47e194b42558
   Files used: INDB.xlsx, recipes.xlsx, recipes_servingsize.xlsx, UK_fct.xlsx
   (INDB.xlsx is identical to anuvaad.org.in's Anuvaad_INDB_2024.11.xlsx.)

2. USDA FoodData Central, SR Legacy (April 2018), public domain (CC0), for single ingredients that INDB lacks
   (plain milk, fruit, raw dal, chicken breast, nuts, oils...).
   https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip (unzipped)

Changes made to INDB:
- Deep-frying oil. In 121 recipes the source counts the whole pan of frying oil as eaten (480 ml of oil for 80 g of
  poori dough gives 738 kcal/100 g). The script rebuilds each recipe's ingredient weight exactly as INDB.do does
  (checked against INDB's own per-serving energy, see --report) and replaces the frying oil with the oil the food
  absorbs, taken as FRY_ABSORPTION of the other ingredients' weight: poori 738 -> 293, aloo samosa 577 -> 252,
  onion pakora 675 -> 222 kcal/100 g.
- Liquid stock. In 40 recipes "1 cup stock" or "480 ml chicken stock" is coded as dry stock cubes (UK_fct K506/K507,
  about 15 g protein and 16 g fat per 100 g, energy not given). That adds a cube's protein and fat for every gram of
  broth (37 g of fat per cup) but no energy, so soups came out at 48 g fat and 318 kcal a bowl. Stock measured by
  volume is counted as water instead (see STOCK_CUBES); energy is unchanged, since the cubes added none.
- Five crisp fried snacks are left out (see EXCLUDE).
- Rows are written per serving. INDB weights are raw-ingredient weights (cooking water included, evaporation
  ignored), so a dish is logged per serving (1 bowl, 1 dosa) and never per gram; dishes without a believable
  serving size fall back to 100 g of the recipe as INDB gives it. That includes serving units INDB marks as
  guessed when one serving would exceed GUESSED_MAX_KCAL (the whole batch as "1 plate").
Everything else is INDB as published.

Usage: python3 tools/generate_food_db.py --indb DIR --usda DIR [--out app/src/main/assets/food_db.json] [--report]
Needs: openpyxl (pip install openpyxl).
"""
import argparse, csv, json, os, re, sys
from fractions import Fraction

FRY_ABSORPTION = 0.15

# ---------------------------------------------------------------------------------------------------------------
# USDA single ingredients: (SR Legacy description, display name, default portion).
# Portion: the start of a USDA portion "modifier" (e.g. "medium", "cup"), "<grams>g <label>" for a fixed
# household measure, or "" for 100 g. Names carry common Indian names so search finds them either way.
USDA = [
    # Grains and flours
    ("Rice, white, long-grain, regular, raw, enriched", "Rice, white, raw (Chawal)", "50g 1 katori raw"),
    ("Rice, white, long-grain, regular, cooked, enriched, with salt", "Rice, white, cooked (Chawal)", "cup"),
    ("Rice, brown, long-grain, raw (Includes foods for USDA's Food Distribution Program)", "Rice, brown, raw", "50g 1 katori raw"),
    ("Rice, brown, long-grain, cooked (Includes foods for USDA's Food Distribution Program)", "Rice, brown, cooked", "cup"),
    ("Rice flour, white, unenriched", "Rice flour (Chawal ka atta)", "cup"),
    ("Wheat flour, whole-grain (Includes foods for USDA's Food Distribution Program)", "Whole wheat flour (Atta)", "30g 1 roti's worth"),
    ("Wheat flour, white, all-purpose, enriched, bleached", "Refined wheat flour (Maida)", "cup"),
    ("Semolina, enriched", "Semolina (Suji/Rava)", "cup"),
    ("Chickpea flour (besan)", "Gram flour (Besan)", "cup"),
    ("Cereals, oats, regular and quick, not fortified, dry", "Oats, rolled, dry", "40g 1 serving"),
    ("Cereals, oats, instant, fortified, plain, prepared with water (boiling water added or microwaved)", "Oats porridge, made with water", "cup"),
    ("Millet, raw", "Millet (proso), raw", "cup"),
    ("Millet, cooked", "Millet (proso), cooked", "cup"),
    ("Sorghum grain", "Sorghum (Jowar)", "cup"),
    ("Quinoa, uncooked", "Quinoa, raw", "cup"),
    ("Quinoa, cooked", "Quinoa, cooked", "cup"),
    ("Barley, pearled, raw", "Barley, raw (Jau)", "cup"),
    ("Corn flour, whole-grain, yellow", "Maize flour (Makki ka atta)", "cup"),
    ("Bread, white, commercially prepared (includes soft bread crumbs)", "Bread, white", "slice"),
    ("Bread, whole-wheat, commercially prepared", "Bread, brown / whole wheat", "slice"),
    ("Bread, naan, plain, commercially prepared, refrigerated", "Naan, plain (store-bought)", "piece"),
    ("Pasta, dry, enriched", "Pasta, dry", "2 oz"),
    ("Pasta, cooked, enriched, without added salt", "Pasta, cooked", "cup"),
    ("Noodles, egg, cooked, enriched, with added salt", "Noodles, egg, cooked", "cup"),
    ("Cereals ready-to-eat, RALSTON Corn Flakes", "Corn flakes", "cup"),
    # Pulses and soy
    ("Pigeon peas (red gram), mature seeds, raw", "Toor dal / Arhar dal (Pigeon peas), raw", "30g 1 serving raw"),
    ("Pigeon peas (red gram), mature seeds, cooked, boiled, without salt", "Toor dal / Arhar dal, boiled", "cup"),
    ("Lentils, raw", "Masoor dal (Lentils), raw", "30g 1 serving raw"),
    ("Lentils, mature seeds, cooked, boiled, without salt", "Masoor dal (Lentils), boiled", "cup"),
    ("Mung beans, mature seeds, raw", "Moong (Mung beans), raw", "30g 1 serving raw"),
    ("Mung beans, mature seeds, cooked, boiled, without salt", "Moong (Mung beans), boiled", "cup"),
    ("Mung beans, mature seeds, sprouted, raw", "Moong sprouts, raw", "cup"),
    ("Chickpeas (garbanzo beans, bengal gram), mature seeds, raw", "Chickpeas / Kabuli chana, raw (Chole)", "30g 1 serving raw"),
    ("Chickpeas (garbanzo beans, bengal gram), mature seeds, cooked, boiled, without salt", "Chickpeas / Kabuli chana, boiled (Chole)", "cup"),
    ("Beans, kidney, red, mature seeds, raw", "Rajma (Kidney beans), raw", "30g 1 serving raw"),
    ("Beans, kidney, red, mature seeds, cooked, boiled, without salt", "Rajma (Kidney beans), boiled", "cup"),
    ("Beans, black, mature seeds, raw", "Black beans, raw", "30g 1 serving raw"),
    ("Cowpeas, common (blackeyes, crowder, southern), mature seeds, raw", "Lobia (Black-eyed peas), raw", "30g 1 serving raw"),
    ("Cowpeas, common (blackeyes, crowder, southern), mature seeds, cooked, boiled, without salt", "Lobia (Black-eyed peas), boiled", "cup"),
    ("Peas, split, mature seeds, cooked, boiled, without salt", "Matar dal (Split peas), boiled", "cup"),
    ("Soybeans, mature seeds, raw", "Soybeans, raw", "cup"),
    ("Soy flour, defatted", "Soya chunks / defatted soy flour, dry", "30g 1 serving dry"),
    ("Tofu, raw, firm, prepared with calcium sulfate", "Tofu, firm", "100g 100 g"),
    # Dairy and eggs
    ("Milk, whole, 3.25% milkfat, with added vitamin D", "Milk, 3.25% fat, close to toned milk (Doodh)", "cup"),
    ("Milk, reduced fat, fluid, 2% milkfat, with added vitamin A and vitamin D", "Milk, 2% fat (Doodh)", "cup"),
    ("Milk, lowfat, fluid, 1% milkfat, with added vitamin A and vitamin D", "Milk, 1% fat, close to double toned (Doodh)", "cup"),
    ("Milk, nonfat, fluid, with added vitamin A and vitamin D (fat free or skim)", "Milk, skimmed / fat free (Doodh)", "cup"),
    ("Milk, indian buffalo, fluid", "Milk, buffalo, close to full cream (Bhains ka doodh)", "cup"),
    ("Milk, goat, fluid, with added vitamin D", "Milk, goat", "cup"),
    ("Milk, dry, nonfat, regular, with added vitamin A and vitamin D", "Milk powder, skimmed", "25g 3 tbsp"),
    ("Yogurt, plain, whole milk", "Curd / Dahi, whole milk", "cup"),
    ("Yogurt, plain, low fat", "Curd / Dahi, low fat", "cup"),
    ("Yogurt, Greek, plain, nonfat (Includes foods for USDA's Food Distribution Program)", "Greek yogurt, plain, nonfat", "container"),
    ("Yogurt, Greek, plain, whole milk", "Greek yogurt, plain, whole milk", "170g 1 container"),
    ("Cheese, cottage, lowfat, 2% milkfat", "Cottage cheese, low fat", "cup"),
    ("Cheese, mozzarella, part skim milk", "Cheese, mozzarella", "oz"),
    ("Cheese, cheddar (Includes foods for USDA's Food Distribution Program)", "Cheese, cheddar", "oz"),
    ("Butter, salted", "Butter, salted (Makhan)", "tbsp"),
    ("Butter, Clarified butter (ghee)", "Ghee (Clarified butter)", "13g 1 tbsp"),
    ("Cream, fluid, heavy whipping", "Cream, heavy (Malai)", "tbsp"),
    ("Beverages, Whey protein powder isolate", "Whey protein isolate powder", "30g 1 scoop"),
    ("Egg, whole, raw, fresh", "Egg, whole, raw (Anda)", "large"),
    ("Egg, whole, cooked, hard-boiled", "Egg, boiled (Ubla anda)", "large"),
    ("Egg, whole, cooked, fried", "Egg, fried", "large"),
    ("Egg, whole, cooked, scrambled", "Egg, scrambled (Anda bhurji, plain)", "large"),
    ("Egg, white, raw, fresh", "Egg white (Ande ki safedi)", "large"),
    ("Egg, yolk, raw, fresh", "Egg yolk", "large"),
    # Meat and fish
    ("Chicken, broiler or fryers, breast, skinless, boneless, meat only, raw", "Chicken breast, raw, skinless", "100g 100 g"),
    ("Chicken, broilers or fryers, breast, meat only, cooked, roasted", "Chicken breast, cooked, skinless", "100g 100 g"),
    ("Chicken, broilers or fryers, leg, meat only, raw", "Chicken leg, raw, skinless", "100g 100 g"),
    ("Chicken, broilers or fryers, leg, meat only, cooked, roasted", "Chicken leg, cooked, skinless", "100g 100 g"),
    ("Chicken, broilers or fryers, thigh, meat only, cooked, roasted", "Chicken thigh, cooked, skinless", "100g 100 g"),
    ("Game meat, goat, raw", "Mutton / Goat meat, raw", "100g 100 g"),
    ("Game meat, goat, cooked, roasted", "Mutton / Goat meat, cooked", "100g 100 g"),
    ("Lamb, ground, raw", "Lamb mince (Keema), raw", "100g 100 g"),
    ("Beef, ground, 90% lean meat / 10% fat, raw", "Beef mince, 90% lean, raw", "100g 100 g"),
    ("Pork, fresh, loin, tenderloin, separable lean only, raw", "Pork tenderloin, raw", "100g 100 g"),
    ("Fish, tilapia, raw", "Fish, tilapia, raw", "100g 100 g"),
    ("Fish, tilapia, cooked, dry heat", "Fish, tilapia, cooked", "100g 100 g"),
    ("Fish, salmon, Atlantic, farmed, raw", "Fish, salmon, raw", "100g 100 g"),
    ("Fish, mackerel, Atlantic, raw", "Fish, mackerel (Atlantic), raw", "100g 100 g"),
    ("Fish, cod, Atlantic, raw", "Fish, white (cod), raw", "100g 100 g"),
    ("Fish, tuna, light, canned in water, drained solids (Includes foods for USDA's Food Distribution Program)", "Tuna, canned in water", "can"),
    ("Fish, sardine, Atlantic, canned in oil, drained solids with bone", "Sardines, canned in oil", "can"),
    ("Crustaceans, shrimp, raw", "Prawns / Shrimp, raw (Jhinga)", "100g 100 g"),
    ("Crustaceans, shrimp, cooked", "Prawns / Shrimp, cooked (Jhinga)", "100g 100 g"),
    # Fruit
    ("Bananas, raw", "Banana (Kela)", "medium"),
    ("Apples, raw, with skin (Includes foods for USDA's Food Distribution Program)", "Apple (Seb)", "medium"),
    ("Mangos, raw", "Mango (Aam)", "cup, pieces"),
    ("Guavas, common, raw", "Guava (Amrood)", "fruit"),
    ("Papayas, raw", "Papaya (Papita)", "cup"),
    ("Oranges, raw, all commercial varieties", "Orange (Santra)", "fruit"),
    ("Grapes, red or green (European type, such as Thompson seedless), raw", "Grapes (Angoor)", "cup"),
    ("Pomegranates, raw", "Pomegranate (Anar)", "cup arils"),
    ("Watermelon, raw", "Watermelon (Tarbooz)", "cup, diced"),
    ("Pineapple, raw, all varieties", "Pineapple (Ananas)", "cup, chunks"),
    ("Sapodilla, raw", "Chikoo (Sapodilla)", "sapodilla"),
    ("Jackfruit, raw", "Jackfruit, ripe (Kathal)", "cup"),
    ("Litchis, raw", "Litchi", "fruit"),
    ("Pears, raw", "Pear (Nashpati)", "medium"),
    ("Figs, raw", "Fig, fresh (Anjeer)", "medium"),
    ("Figs, dried, uncooked", "Fig, dried (Anjeer)", "fig"),
    ("Strawberries, raw", "Strawberries", "cup"),
    ("Kiwifruit, green, raw", "Kiwi", "fruit"),
    ("Melons, cantaloupe, raw", "Muskmelon (Kharbuja)", "cup"),
    ("Custard-apple, (bullock's-heart), raw", "Custard apple (Sitaphal)", "100g 100 g"),
    ("Plums, raw", "Plum (Aloo bukhara)", "fruit"),
    ("Peaches, yellow, raw", "Peach (Aadu)", "medium"),
    ("Cherries, sweet, raw", "Cherries", "cup"),
    ("Avocados, raw, all commercial varieties", "Avocado", "avocado"),
    ("Dates, medjool", "Dates, medjool (Khajur)", "date"),
    ("Dates, deglet noor", "Dates, dry (Khajur)", "date"),
    ("Raisins, dark, seedless (Includes foods for USDA's Food Distribution Program)", "Raisins (Kishmish)", "small box"),
    ("Apricots, dried, sulfured, uncooked", "Apricots, dried (Khubani)", "half"),
    ("Nuts, coconut meat, raw", "Coconut, fresh (Nariyal)", "cup, shredded"),
    ("Nuts, coconut water (liquid from coconuts)", "Coconut water (Nariyal pani)", "cup"),
    ("Tamarinds, raw", "Tamarind (Imli)", "100g 100 g"),
    ("Lemon juice, raw", "Lemon juice (Nimbu)", "15g 1 tbsp"),
    ("Orange juice, raw (Includes foods for USDA's Food Distribution Program)", "Orange juice, fresh", "cup"),
    # Vegetables
    ("Potatoes, flesh and skin, raw", "Potato, raw (Aloo)", "potato medium"),
    ("Potatoes, boiled, cooked without skin, flesh, without salt", "Potato, boiled (Aloo)", "medium"),
    ("Sweet potato, raw, unprepared (Includes foods for USDA's Food Distribution Program)", "Sweet potato (Shakarkandi)", "130g 1 medium"),
    ("Onions, raw", "Onion (Pyaaz)", "medium"),
    ("Tomatoes, red, ripe, raw, year round average", "Tomato (Tamatar)", "medium"),
    ("Spinach, raw", "Spinach (Palak)", "cup"),
    ("Cauliflower, raw", "Cauliflower (Gobhi)", "cup"),
    ("Cabbage, raw", "Cabbage (Patta gobhi)", "cup, chopped"),
    ("Carrots, raw", "Carrot (Gajar)", "medium"),
    ("Peas, green, raw", "Green peas (Matar)", "cup"),
    ("Okra, raw", "Okra / Ladyfinger (Bhindi)", "cup"),
    ("Eggplant, raw", "Brinjal / Eggplant (Baingan)", "cup"),
    ("Cucumber, with peel, raw", "Cucumber (Kheera)", "cup"),
    ("Beans, snap, green, raw", "Green beans (French beans)", "cup"),
    ("Gourd, white-flowered (calabash), raw", "Bottle gourd (Lauki/Ghiya/Dudhi)", "cup"),
    ("Balsam-pear (bitter gourd), pods, raw", "Bitter gourd (Karela)", "cup"),
    ("Pumpkin, raw", "Pumpkin (Kaddu)", "cup"),
    ("Radishes, raw", "Radish (Mooli)", "cup"),
    ("Beets, raw", "Beetroot (Chukandar)", "beet"),
    ("Broccoli, raw", "Broccoli", "cup"),
    ("Mushrooms, white, raw", "Mushrooms (Khumb)", "cup"),
    ("Peppers, sweet, green, raw", "Capsicum, green (Shimla mirch)", "medium"),
    ("Corn, sweet, yellow, raw", "Sweet corn (Bhutta)", "ear"),
    ("Lettuce, iceberg (includes crisphead types), raw", "Lettuce", "cup"),
    ("Garlic, raw", "Garlic (Lehsun)", "clove"),
    ("Ginger root, raw", "Ginger (Adrak)", "tsp"),
    ("Coriander (cilantro) leaves, raw", "Coriander leaves (Dhaniya)", "cup"),
    ("Drumstick pods, raw", "Drumstick (Moringa / Sahjan)", "cup"),
    ("Lotus root, raw", "Lotus stem (Kamal kakdi)", ""),
    ("Taro, raw", "Colocasia (Arbi)", "cup"),
    ("Yam, raw", "Yam (Suran/Jimikand)", "cup"),
    ("Cassava, raw", "Tapioca (Kappa/Cassava)", "cup"),
    # Nuts, seeds, oils, sweeteners
    ("Nuts, almonds", "Almonds (Badam)", "oz"),
    ("Nuts, cashew nuts, raw", "Cashews (Kaju)", "oz"),
    ("Nuts, walnuts, english", "Walnuts (Akhrot)", "oz"),
    ("Nuts, pistachio nuts, raw", "Pistachios (Pista)", "oz"),
    ("Peanuts, all types, raw", "Peanuts, raw (Moongphali)", "oz"),
    ("Peanuts, all types, dry-roasted, without salt", "Peanuts, roasted (Moongphali)", "oz"),
    ("Peanut butter, smooth style, without salt", "Peanut butter", "tbsp"),
    ("Seeds, flaxseed", "Flax seeds (Alsi)", "tbsp, whole"),
    ("Seeds, chia seeds, dried", "Chia seeds", "oz"),
    ("Seeds, pumpkin and squash seed kernels, dried", "Pumpkin seeds", "oz"),
    ("Seeds, sunflower seed kernels, dried", "Sunflower seeds", "28g 1 oz"),
    ("Seeds, sesame seeds, whole, dried", "Sesame seeds (Til)", "tbsp"),
    ("Spices, fenugreek seed", "Fenugreek seeds (Methi dana)", "tsp"),
    ("Oil, mustard", "Mustard oil (Sarson ka tel)", "tbsp"),
    ("Oil, sunflower, linoleic, (approx. 65%)", "Sunflower oil", "tbsp"),
    ("Oil, coconut", "Coconut oil", "tbsp"),
    ("Oil, olive, salad or cooking", "Olive oil", "tablespoon"),
    ("Oil, soybean, salad or cooking", "Soybean oil", "tbsp"),
    ("Oil, peanut, salad or cooking", "Groundnut oil", "tbsp"),
    ("Oil, rice bran", "Rice bran oil", "tablespoon"),
    ("Sugars, granulated", "Sugar (Cheeni)", "tsp"),
    ("Sugars, brown", "Brown sugar", "tsp"),
    ("Honey", "Honey (Shahad)", "tbsp"),
    ("Jams and preserves", "Jam", "tbsp"),
    ("Chocolate, dark, 70-85% cacao solids", "Dark chocolate 70-85%", "oz"),
    ("Ice creams, vanilla", "Ice cream, vanilla", "66g 1/2 cup"),
    ("Popcorn, microwave, low fat and sodium", "Popcorn, microwave, low fat", "oz"),
    # Drinks
    ("Beverages, tea, black, brewed, prepared with tap water", "Tea, black, no milk or sugar", "cup"),
    ("Beverages, coffee, brewed, prepared with tap water", "Coffee, black, no milk or sugar", "cup"),
    ("Beverages, carbonated, cola, without caffeine", "Soft drink, cola", "340g 1 can (330 ml)"),
    ("Alcoholic beverage, beer, regular, all", "Beer, regular", "can"),
]

# Largest believable weight for one INDB serving unit; anything heavier means the recipe's serving count is off,
# so the food falls back to 100 g servings.
UNIT_MAX_G = {
    "biscuit": 30, "cookie": 40, "poori": 60, "roti": 80, "chapati": 80, "parantha": 150, "dosa": 200,
    "ladoo": 80, "burfi": 60, "vada": 80, "idli": 80, "cheela": 150, "pancake": 150, "cutlet": 120,
    "kabab": 120, "tablespoon": 30, "teaspoon": 10, "slice": 150, "large slice": 200, "triangle": 120,
    "toasted triangle": 120, "tart": 120, "pastry": 150, "egg": 200, "sandwich": 300, "piece": 250,
    "appam": 100,
}
UNIT_DEFAULT_MAX_G = 600
# INDB marks 199 serving units "guessed". Most are fine, but some make the whole batch one serving (bhel puri at
# 1,210 kcal a plate), so a guessed serving with more energy than this also falls back to 100 g. (Energy, not
# weight: raw weights include cooking water, so a 262 kcal bowl of dal can weigh 350 g.)
GUESSED_MAX_KCAL = 600

# Crisp fried snacks. INDB ignores the water that frying drives out, so with or without the oil fix their values are
# far off (banana chips 666 or 213 kcal/100 g against roughly 520 measured). Left out rather than shown wrong;
# packaged versions are on Open Food Facts.
EXCLUDE = {"OSR113", "OSR119", "OSR147", "OSR148", "ASC460"}

# Dry stock cubes (UK_fct). A recipe line of these measured in cups or ml is broth, so it is counted as water.
STOCK_CUBES = {"K506", "K507"}


def round1(x):
    return round(float(x) + 0.0, 1)


def row(fid, name, per100, grams, size, unit):
    """One food as the app stores it: macros for `size` `unit`, which weighs `grams` (per 100 g values in)."""
    k, p, c, f = (v * grams / 100 for v in per100)
    return {"id": fid, "n": name, "ss": size, "su": unit, "k": round1(k), "p": round1(p), "c": round1(c), "f": round1(f)}


def xlsx_rows(path):
    import openpyxl
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    rows = list(wb.worksheets[0].iter_rows(values_only=True))
    head = [str(h).strip() if h is not None else "" for h in rows[0]]
    return [dict(zip(head, r)) for r in rows[1:] if any(v not in (None, "") for v in r)]


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def amount_g(amount, unit, code, name):
    """Grams for one recipe line, mirroring the unit rules in INDB.do (applied in the same order)."""
    if amount is None or not code:
        return None
    cat, a, g = code[0], amount, None
    rules = [
        (unit == "g", 1),
        (unit == "tsp" and cat in "GHBU", 2), (unit == "tbsp" and cat in "DGHBU", 7),
        (unit == "tsp" and cat == "D", 2), (unit == "tbsp" and cat == "F", 40),
        (unit == "ml" and cat in "GHIVX", 1), (unit == "tsp" and cat == "A", 2.5),
        (unit == "tsp" and cat == "M", 5), (unit == "tbsp" and cat == "A", 7),
        (unit == "tsp" and cat == "V", 2), (unit == "tsp" and cat == "T", 4),
        (unit == "tbsp" and cat == "T", 13.5), (unit == "tsp" and cat in "XW", 6),
        (unit == "tbsp" and cat in "XW", 20), (unit == "tsp" and cat == "I", 4),
        (unit == "tbsp" and cat == "I", 12), (unit == "C" and cat == "I", 200),
        (unit == "tbsp" and cat == "Z", 15),
        (unit == "tsp" and cat == "L", 5), (unit == "tbsp" and cat == "L", 15),
        (unit == "C" and cat == "L", 240), (unit == "ml" and cat == "L", 1),
        (unit == "sprig" and cat in "GUCA", 2), (unit == "tsp" and cat == "C", 0.5),
        (unit == "tbsp" and cat == "C", 5), (unit == "tsp" and cat == "T", 4),
        (unit == "ml" and cat == "T", 1), (unit == "C", 240),
        (unit == "ml" and cat == "K", 1), (unit == "ml" and cat == "E", 1),
        (unit == "tsp" and cat in "KE", 5), (unit == "tsp" and cat == "E", 5),
        (unit == "tbsp" and cat in "KE", 15), (unit == "tbsp" and cat == "E", 15),
        (unit == "tbsp" and cat == "V", 8), (unit == "C" and cat == "L", 240),
        (unit == "small" and cat == "G", 2), (name == "FENUGREEK LEAVES", 2),
    ]
    for cond, factor in rules:
        if cond:
            g = a * factor
    if unit == "pinch":
        g = 0.125
    return g


def is_frying_oil(row):
    """Deep-frying oil whose quantity the recipe never gave (INDB imputed 240-480 ml for it)."""
    code = str(row.get("food_code") or "")
    text = f"{row.get('ingredient_name_org')}|{row.get('amount_org')}|{row.get('unit_org')}".lower()
    given = re.fullmatch(r"[\d.\s/]+(to[\d.\s]+)?", str(row.get("amount_org") or "").strip()) is not None
    return code.startswith("T") and re.search(r"\bfry|fryng|frying", text) is not None and not given


def clean_name(s):
    s = re.sub(r"\s+", " ", str(s)).strip()
    s = s.replace("( ", "(").replace(" )", ")")
    return s[:1].upper() + s[1:]


def build_indb(d, report):
    nutr = xlsx_rows(os.path.join(d, "INDB.xlsx"))
    recipes = xlsx_rows(os.path.join(d, "recipes.xlsx"))
    servings = {r["recipe_code"]: r for r in xlsx_rows(os.path.join(d, "recipes_servingsize.xlsx"))}
    uk = {str(r["food_code"]): r for r in xlsx_rows(os.path.join(d, "UK_fct.xlsx"))}

    weight, oil, stock = {}, {}, {}
    for r in recipes:
        code = r["recipe_code"]
        unit = str(r.get("unit") or "").strip()
        g = amount_g(num(r.get("amount")), unit, str(r.get("food_code") or ""), str(r.get("food_name") or ""))
        if g is None:
            continue
        weight[code] = weight.get(code, 0.0) + g
        if is_frying_oil(r):
            fc = uk[str(r["food_code"])]
            oil.setdefault(code, []).append((g, fc))
        if str(r.get("food_code")) in STOCK_CUBES and unit in ("C", "ml"):
            stock.setdefault(code, []).append((g, uk[str(r["food_code"])]))

    out, checked, mismatched, fixed, broth = [], 0, [], [], []
    for r in nutr:
        code = r["food_code"]
        if code in EXCLUDE:
            continue
        k100, p100, c100, f100 = (num(r[k]) or 0.0 for k in ("energy_kcal", "protein_g", "carb_g", "fat_g"))
        w = weight.get(code)
        s = servings.get(code, {})
        n_serv, size, unit = num(s.get("no_of_servings")), num(s.get("size_of_servings")), str(r.get("servings_unit") or "").strip()
        ks = num(r.get("unit_serving_energy_kcal"))

        # Cross-check the reproduced recipe weight against the per-serving energy INDB published.
        if w and ks and n_serv and size and k100:
            checked += 1
            w_pub = ks * n_serv * size / k100 * 100
            if abs(w_pub - w) / w_pub > 0.02:
                mismatched.append((code, round(w), round(w_pub)))

        if code in stock and w:
            # Same weight, minus the cubes' nutrients ("N" and "Tr" count as 0, as in INDB.do). UK_fct values are
            # per 100 g, so a line adds g * value / 100, which is g * value / w per 100 g of the recipe.
            before = (round(p100, 1), round(f100, 1))
            k100, p100, c100, f100 = (
                max(0.0, v - sum(g * (num(fc[key]) or 0.0) for g, fc in stock[code]) / w)
                for v, key in ((k100, "energy_kcal"), (p100, "protein_g"), (c100, "carb_g"), (f100, "fat_g")))
            broth.append((code, r["food_name"], before, (round(p100, 1), round(f100, 1))))

        if code in oil and w:
            total = {k: v * w / 100 for k, v in (("k", k100), ("p", p100), ("c", c100), ("f", f100))}
            o = sum(g for g, _ in oil[code])
            rest = w - o
            absorbed = min(o, FRY_ABSORPTION * rest)
            removed = o - absorbed
            per_g = lambda key: sum(g * (num(fc[key]) or 0) for g, fc in oil[code]) / o / 100
            total["k"] -= removed * per_g("energy_kcal")
            total["p"] -= removed * per_g("protein_g")
            total["c"] -= removed * per_g("carb_g")
            total["f"] -= removed * per_g("fat_g")
            w2 = rest + absorbed
            before = k100
            k100, p100, c100, f100 = (max(0.0, total[k] * 100 / w2) for k in "kpcf")
            if round(before) != round(k100):
                fixed.append((code, r["food_name"], round(before), round(k100)))
            w = w2

        # INDB weights are raw-ingredient weights (cooking water in, evaporation ignored), so grams of a cooked
        # dish do not match them. Dishes with a believable serving are logged per serving (1 bowl, 1 dosa) and
        # never in grams; the rest fall back to 100 g of the recipe as INDB gives it.
        grams = w / (n_serv * size) if w and n_serv and size and unit else None
        guessed = "guessed" in f"{s.get('remarks_1')} {s.get('remarks_2')}"
        if (grams and 3 <= grams <= UNIT_MAX_G.get(unit.lower(), UNIT_DEFAULT_MAX_G)
                and not (guessed and k100 * grams / 100 > GUESSED_MAX_KCAL)):
            out.append(row("INDB:" + code, clean_name(r["food_name"]), (k100, p100, c100, f100), grams, 1, unit.lower()))
        else:
            out.append(row("INDB:" + code, clean_name(r["food_name"]), (k100, p100, c100, f100), 100, 100, "g"))

    if report:
        print(f"INDB: {len(out)} recipes; weight check {checked - len(mismatched)}/{checked} within 2%", file=sys.stderr)
        for m in mismatched[:15]:
            print("  weight mismatch", m, file=sys.stderr)
        print(f"INDB: frying oil corrected in {len(fixed)} recipes (kcal/100 g before -> after):", file=sys.stderr)
        for f in fixed:
            print("  ", f, file=sys.stderr)
        print(f"INDB: stock counted as water in {len(broth)} recipes (protein, fat g/100 g before -> after):", file=sys.stderr)
        for b in broth:
            print("  ", b, file=sys.stderr)
        print(f"INDB: {sum(f['su'] != 'g' for f in out)} with a natural serving", file=sys.stderr)
    return out


def build_usda(d, report):
    foods = {r["description"]: r["fdc_id"] for r in csv.DictReader(open(os.path.join(d, "food.csv"), encoding="utf-8"))}
    wanted = {foods[desc]: (desc, name, portion) for desc, name, portion in USDA if desc in foods}
    missing = [desc for desc, _, _ in USDA if desc not in foods]
    if missing:
        sys.exit("USDA descriptions not found:\n  " + "\n  ".join(missing))
    nut = {}
    ids = {"1008": "k", "1003": "p", "1005": "c", "1004": "f"}
    for r in csv.DictReader(open(os.path.join(d, "food_nutrient.csv"), encoding="utf-8")):
        if r["fdc_id"] in wanted and r["nutrient_id"] in ids:
            nut.setdefault(r["fdc_id"], {})[ids[r["nutrient_id"]]] = float(r["amount"])
    portions = {}
    for r in csv.DictReader(open(os.path.join(d, "food_portion.csv"), encoding="utf-8")):
        if r["fdc_id"] in wanted and r["gram_weight"]:
            portions.setdefault(r["fdc_id"], []).append(r)

    norm = lambda t: re.sub(r"[,\s]+", " ", t).strip().lower()
    out, errors = [], []
    for fdc, (desc, name, portion) in wanted.items():
        n = nut[fdc]
        per100 = tuple(n.get(k, 0.0) for k in "kpcf")
        grams, label = None, None
        m = re.fullmatch(r"(\d+(?:\.\d+)?)g (.+)", portion)
        if m:
            grams, label = float(m.group(1)), m.group(2)
        elif portion:
            match = None
            for p in portions.get(fdc, []):
                mod = p["modifier"].strip()
                full = (p["amount"] + " " + mod) if p["amount"] not in ("", "1", "1.0") else mod
                if norm(mod).startswith(norm(portion)) or norm(full).startswith(norm(portion)):
                    match = (p, full)
                    break
            if match is None:
                errors.append(f"no portion '{portion}' for {desc}: " + " | ".join(p["modifier"] for p in portions.get(fdc, [])))
                continue
            p, full = match
            label = re.sub(r"\s*\(.*?\)", "", full)
            label = re.sub(r",?\s*(without refuse|with pits|yields|ns as to .*)", "", label, flags=re.I).strip().rstrip(",")
            amt = p["amount"] if p["amount"] not in ("", "1", "1.0") else "1"
            if not label.startswith(amt):
                label = f"{amt} {label}"
            grams, label = float(p["gram_weight"]), label.lower().replace("tablespoon", "tbsp")
        if grams is None or label == "100 g":
            out.append(row("USDA:" + fdc, name, per100, 100, 100, "g"))
        else:
            # "0.5 cup arils" -> 0.5 x "cup arils (87 g)", so the app shows "1 serving = 0.5 cup arils (87 g)".
            m = re.fullmatch(r"(\d+(?:\.\d+)?|\d+/\d+) (.+)", label)
            amount, unit = (m.group(1), m.group(2)) if m else ("1", label)
            size = float(Fraction(amount))
            out.append(row("USDA:" + fdc, name, per100, grams, size, f"{unit} ({round(grams)} g)"))
    if errors:
        sys.exit("\n".join(errors))
    if report:
        print(f"USDA: {len(out)} ingredients", file=sys.stderr)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--indb", required=True)
    ap.add_argument("--usda", required=True)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "app/src/main/assets/food_db.json"))
    ap.add_argument("--report", action="store_true")
    a = ap.parse_args()
    foods = build_indb(a.indb, a.report) + build_usda(a.usda, a.report)
    ids = [f["id"] for f in foods]
    assert len(ids) == len(set(ids)), "duplicate ids"
    keys = [(f["id"].split(":")[0], f["n"].lower()) for f in foods]
    assert len(keys) == len(set(keys)), "duplicate names (the app matches saved foods by source and name)"
    doc = {
        "format": "gains-goblin-foods", "version": 1,
        "sources": {
            "INDB": "Indian Nutrient Databank (Vijayakumar et al., Curr Dev Nutr 2024, CC BY 4.0); "
                    "deep-frying oil adjusted to the amount absorbed, liquid stock counted as water",
            "USDA": "USDA FoodData Central SR Legacy 2018 (public domain)",
        },
        "foods": foods,
    }
    with open(a.out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, separators=(",", ":"))
        fh.write("\n")
    print(f"wrote {len(foods)} foods to {os.path.normpath(a.out)}", file=sys.stderr)


if __name__ == "__main__":
    main()
