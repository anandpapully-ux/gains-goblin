#!/usr/bin/env python3
import json, sys, os
# Regenerates app/src/main/assets/exercise_library.json and assets/templates/*.json.
# Usage: python3 tools/generate_plan_assets.py app/src/main/assets
out = sys.argv[1]
C, I, T, K = "COMPOUND", "ISOLATION", "TIMED", "CARDIO"
def ex(slug, name, muscle, sec, equip, rule, uni=False, alts=(), instr=""):
    d = {"slug": slug, "name": name, "primaryMuscle": muscle, "secondaryMuscles": list(sec),
         "equipment": equip, "rule": rule, "unilateral": uni}
    if alts: d["alternatives"] = list(alts)
    if instr: d["instructions"] = instr
    return d
lib = [
 # Barbell
 ex("barbell_back_squat","Barbell Back Squat","Quadriceps",["Glutes","Adductors","Core"],"Barbell, Squat Rack",C,alts=["Hack Squat","Leg Press"],instr="Brace, sit down between the hips, keep the whole foot planted."),
 ex("barbell_front_squat","Barbell Front Squat","Quadriceps",["Glutes","Upper Back","Core"],"Barbell, Squat Rack",C,alts=["Goblet Squat"]),
 ex("barbell_bench_press","Barbell Bench Press","Chest",["Triceps","Anterior Deltoids"],"Barbell, Flat Bench",C,alts=["Machine Chest Press","Flat Dumbbell Bench Press"],instr="Shoulder blades pinned, feet planted, touch the lower chest."),
 ex("incline_barbell_bench_press","Incline Barbell Bench Press","Upper Chest",["Triceps","Anterior Deltoids"],"Barbell, Incline Bench",C,alts=["Low-Incline Dumbbell Press"]),
 ex("close_grip_bench_press","Close-Grip Bench Press","Triceps",["Chest","Anterior Deltoids"],"Barbell, Flat Bench",C),
 ex("barbell_overhead_press","Barbell Overhead Press","Anterior Deltoids",["Triceps","Side Deltoids","Core"],"Barbell",C,alts=["Seated Dumbbell Shoulder Press","Machine Shoulder Press"]),
 ex("barbell_row","Barbell Bent-Over Row","Upper Back",["Lats","Biceps","Rear Deltoids"],"Barbell",C,alts=["Seated Cable Row","Chest-Supported Dumbbell Row"]),
 ex("barbell_deadlift","Barbell Deadlift","Hamstrings",["Glutes","Upper Back","Forearms"],"Barbell",C,instr="Bar over mid-foot, push the floor away, lock out with the glutes."),
 ex("barbell_romanian_deadlift","Barbell Romanian Deadlift","Hamstrings",["Glutes","Lower Back"],"Barbell",C,alts=["Dumbbell Romanian Deadlift (RDL)"]),
 ex("barbell_hip_thrust","Barbell Hip Thrust","Glutes",["Hamstrings"],"Barbell, Bench",C,alts=["Single-Leg Dumbbell Hip Thrust"]),
 ex("barbell_curl","Barbell Curl","Biceps",["Forearms"],"Barbell or EZ Bar",I,alts=["Cable Curl"]),
 # Bodyweight
 ex("pull_up","Pull-Up","Lats",["Biceps","Upper Back"],"Pull-Up Bar",C,alts=["Lat Pulldown"],instr="Log added weight (0 for bodyweight)."),
 ex("chin_up","Chin-Up","Lats",["Biceps"],"Pull-Up Bar",C,alts=["Lat Pulldown"],instr="Log added weight (0 for bodyweight)."),
 ex("push_up","Push-Up","Chest",["Triceps","Anterior Deltoids","Core"],"Bodyweight",C,instr="Log added weight (0 for bodyweight)."),
 ex("parallel_bar_dip","Dip","Triceps",["Chest","Anterior Deltoids"],"Parallel Bars",C,instr="Log added weight (0 for bodyweight)."),
 ex("inverted_row","Inverted Row","Upper Back",["Lats","Biceps"],"Bar or Rings",C),
 ex("hanging_leg_raise","Hanging Leg Raise","Core",["Hip Flexors"],"Pull-Up Bar",I),
 ex("ab_wheel_rollout","Ab Wheel Rollout","Core",[],"Ab Wheel",I),
 ex("side_plank","Side Plank","Core",["Obliques"],"Mat",T,uni=True),
 ex("glute_bridge","Glute Bridge","Glutes",["Hamstrings"],"Bodyweight or Dumbbell",I),
 ex("back_extension","Back Extension","Lower Back",["Glutes","Hamstrings"],"Back Extension Bench",I),
 # Machines and cables
 ex("lat_pulldown","Lat Pulldown","Lats",["Biceps","Upper Back"],"Cable Machine",C,alts=["Pull-Up"]),
 ex("seated_cable_row","Seated Cable Row","Upper Back",["Lats","Biceps"],"Cable Machine",C,alts=["Barbell Bent-Over Row"]),
 ex("leg_press","Leg Press","Quadriceps",["Glutes"],"Leg Press Machine",C,alts=["Hack Squat"]),
 ex("hack_squat","Hack Squat","Quadriceps",["Glutes"],"Hack Squat Machine",C,alts=["Leg Press"]),
 ex("leg_extension","Leg Extension","Quadriceps",[],"Leg Extension Machine",I),
 ex("seated_leg_curl","Seated Leg Curl","Hamstrings",[],"Leg Curl Machine",I,alts=["Lying Leg Curl (Machine)"]),
 ex("lying_leg_curl_machine","Lying Leg Curl (Machine)","Hamstrings",[],"Leg Curl Machine",I,alts=["Seated Leg Curl"]),
 ex("machine_chest_press","Machine Chest Press","Chest",["Triceps","Anterior Deltoids"],"Chest Press Machine",C),
 ex("machine_shoulder_press","Machine Shoulder Press","Anterior Deltoids",["Triceps"],"Shoulder Press Machine",C),
 ex("pec_deck","Pec Deck Fly","Chest",[],"Pec Deck Machine",I,alts=["Cable Fly"]),
 ex("cable_fly","Cable Fly","Chest",["Anterior Deltoids"],"Cable Machine",I,alts=["Pec Deck Fly","Dumbbell Fly"]),
 ex("cable_lateral_raise","Cable Lateral Raise","Side Deltoids",[],"Cable Machine",I,uni=True,alts=["Dumbbell Lateral Raises"]),
 ex("face_pull","Face Pull","Rear Deltoids",["Upper Back"],"Cable Machine, Rope",I,alts=["Incline Chest-Supported Reverse Fly"]),
 ex("triceps_pushdown","Triceps Pushdown","Triceps",[],"Cable Machine",I),
 ex("overhead_cable_triceps_extension","Overhead Cable Triceps Extension","Triceps",[],"Cable Machine, Rope",I,alts=["Overhead Dumbbell Triceps Extension"]),
 ex("cable_curl","Cable Curl","Biceps",[],"Cable Machine",I),
 ex("cable_crunch","Cable Crunch","Core",[],"Cable Machine, Rope",I),
 ex("standing_calf_raise_machine","Standing Calf Raise (Machine)","Calves",[],"Calf Raise Machine",I,alts=["Standing Dumbbell Calf Raises"]),
 ex("seated_calf_raise","Seated Calf Raise","Calves",[],"Seated Calf Machine",I),
 ex("hip_abduction_machine","Hip Abduction (Machine)","Glutes",[],"Hip Abduction Machine",I),
 # Dumbbells and kettlebells
 ex("goblet_squat","Goblet Squat","Quadriceps",["Glutes","Core"],"Dumbbell or Kettlebell",C),
 ex("dumbbell_bent_over_row","Dumbbell Bent-Over Row","Upper Back",["Lats","Biceps"],"Dumbbells",C),
 ex("dumbbell_fly","Dumbbell Fly","Chest",[],"Dumbbells, Flat Bench",I),
 ex("arnold_press","Arnold Press","Anterior Deltoids",["Side Deltoids","Triceps"],"Dumbbells, Bench",C),
 ex("incline_dumbbell_curl","Incline Dumbbell Curl","Biceps",[],"Dumbbells, Incline Bench",I),
 ex("concentration_curl","Concentration Curl","Biceps",[],"Dumbbell, Bench",I,uni=True),
 ex("dumbbell_shrug","Dumbbell Shrug","Traps",[],"Dumbbells",I),
 ex("dumbbell_step_up","Dumbbell Step-Up","Quadriceps",["Glutes"],"Dumbbells, Box or Bench",C,uni=True),
 ex("dumbbell_pullover","Dumbbell Pullover","Lats",["Chest"],"Dumbbell, Flat Bench",I),
 ex("kettlebell_swing","Kettlebell Swing","Glutes",["Hamstrings","Core"],"Kettlebell",C),
 ex("farmers_carry","Farmer's Carry","Forearms",["Traps","Core"],"Dumbbells or Kettlebells",T),
 # Cardio
 ex("zone2_cardio","Easy Cardio (Zone 2)","Cardiovascular System",["Legs"],"Any: walk, bike, rower",K,instr="Conversational pace."),
 ex("running","Running","Cardiovascular System",["Legs"],"Outdoors or Treadmill",K),
 ex("cycling","Cycling","Cardiovascular System",["Legs"],"Bike",K),
 ex("rowing_machine","Rowing Machine","Cardiovascular System",["Upper Back","Legs"],"Rower",K),
 ex("interval_cardio","Interval Cardio","Cardiovascular System",[],"Any",K,instr="e.g. 30 s hard / 90 s easy."),
]
slugs = [e["slug"] for e in lib]
assert len(slugs) == len(set(slugs)), "duplicate slug"
json.dump({"exercises": lib}, open(os.path.join(out, "exercise_library.json"), "w"), indent=1)

rules = {e["slug"]: e["rule"] for e in lib}
# Exercises that come from the original plan (workout_plan.json) and are also always in the library.
orig = {"flat_dumbbell_bench_press":C,"chest_supported_dumbbell_row":C,"low_incline_dumbbell_press":C,"dumbbell_lateral_raises":I,
 "supinated_dumbbell_bicep_curls":I,"lying_dumbbell_triceps_extensions":I,"heel_elevated_dumbbell_squat":C,"dumbbell_romanian_deadlift":C,
 "bulgarian_split_squat":C,"standing_dumbbell_calf_raises":I,"plank_or_reverse_crunch":T,"single_arm_tripod_dumbbell_row":C,
 "neutral_grip_chest_supported_lat_row":C,"seated_dumbbell_shoulder_press":C,"incline_chest_supported_reverse_fly":I,"dumbbell_hammer_curls":I,
 "overhead_dumbbell_triceps_extension":I,"dumbbell_reverse_lunges":C,"lying_dumbbell_leg_curl":I,"single_leg_or_b_stance_dumbbell_hip_thrust":C,
 "single_leg_standing_calf_raises":I,"dead_bugs":T}
rules.update(orig)
heavy = {"barbell_back_squat","barbell_deadlift","barbell_front_squat"}
def it(slug, sets, lo, hi, per_side=False, notes=""):
    r = rules[slug]
    if r == C: rest, rir = ([150,240],[1,3]) if slug in heavy else ([120,180],[1,2])
    elif r == I: rest, rir = [60,90],[0,2]
    elif r == T: rest, rir = [45,60],[0,0]
    else: rest, rir = [0,0],[0,0]
    d = {"exercise": slug, "sets": sets, "reps": [lo,hi], "rest": rest, "rir": rir}
    if per_side: d["perSide"] = True
    if notes: d["notes"] = notes
    return d
def day(wd, title, mins, items, typ="HYPERTROPHY"):
    return {"weekday": wd, "title": title, "type": typ, "minutes": mins, "exercises": items}
def plan(fname, name, desc, days):
    for d in days:
        for i in d["exercises"]: assert i["exercise"] in rules, i["exercise"]
    json.dump({"format":"gains-goblin-plan","version":1,"name":name,"description":desc,"days":days},
              open(os.path.join(out,"templates",fname),"w"), indent=1)

fbA = [it("barbell_back_squat",3,5,8), it("barbell_bench_press",3,6,10), it("barbell_row",3,6,10),
       it("dumbbell_lateral_raises",2,12,20), it("triceps_pushdown",2,10,15), it("plank_or_reverse_crunch",2,30,60)]
fbB = [it("barbell_romanian_deadlift",3,6,10), it("barbell_overhead_press",3,6,10), it("lat_pulldown",3,8,12),
       it("leg_press",2,10,15), it("face_pull",2,12,20), it("hanging_leg_raise",2,8,15)]
fbC = [it("barbell_hip_thrust",3,8,12), it("low_incline_dumbbell_press",3,8,12), it("seated_cable_row",3,8,12),
       it("seated_leg_curl",2,10,15), it("barbell_curl",2,8,12), it("overhead_cable_triceps_extension",2,10,15)]
plan("10_full_body_3day_gym.json","Full Body 3-Day (Gym)",
     "Three full-body sessions a week (Mon/Wed/Fri) with barbells, cables and machines. A good start for beginners and busy weeks.",
     [day(1,"Full Body A",60,fbA), day(3,"Full Body B",60,fbB), day(5,"Full Body C",60,fbC)])

ulUA = [it("barbell_bench_press",3,6,10), it("barbell_row",3,6,10), it("seated_dumbbell_shoulder_press",2,8,12),
        it("lat_pulldown",2,8,12), it("dumbbell_lateral_raises",2,12,20), it("triceps_pushdown",2,10,15), it("barbell_curl",2,8,12)]
ulLA = [it("barbell_back_squat",3,5,8), it("barbell_romanian_deadlift",3,6,10), it("leg_extension",2,10,15),
        it("seated_leg_curl",2,10,15), it("standing_calf_raise_machine",3,10,15), it("cable_crunch",2,10,15)]
ulUB = [it("incline_barbell_bench_press",3,6,10), it("pull_up",3,6,10), it("seated_cable_row",3,8,12),
        it("cable_lateral_raise",2,12,20,True), it("face_pull",2,12,20), it("incline_dumbbell_curl",2,10,15),
        it("overhead_cable_triceps_extension",2,10,15)]
ulLB = [it("barbell_deadlift",3,3,6), it("leg_press",3,8,12), it("bulgarian_split_squat",2,8,12,True),
        it("lying_leg_curl_machine",2,10,15), it("seated_calf_raise",3,12,20), it("hanging_leg_raise",2,8,15)]
plan("20_upper_lower_4day_gym.json","Upper/Lower 4-Day (Gym)",
     "Upper and lower body twice a week (Mon/Tue/Thu/Fri) with barbells, cables and machines. Intermediate lifters.",
     [day(1,"Upper A",65,ulUA), day(2,"Lower A",60,ulLA), day(4,"Upper B",65,ulUB), day(5,"Lower B",60,ulLB)])

push = [it("barbell_bench_press",3,6,10), it("barbell_overhead_press",3,6,10), it("low_incline_dumbbell_press",2,8,12),
        it("cable_lateral_raise",3,12,20,True), it("triceps_pushdown",2,10,15), it("overhead_cable_triceps_extension",2,10,15)]
pull = [it("pull_up",3,6,10), it("barbell_row",3,6,10), it("seated_cable_row",2,8,12),
        it("face_pull",3,12,20), it("barbell_curl",2,8,12), it("dumbbell_hammer_curls",2,10,15)]
legs = [it("barbell_back_squat",3,5,8), it("barbell_romanian_deadlift",3,6,10), it("leg_press",2,10,15),
        it("seated_leg_curl",2,10,15), it("standing_calf_raise_machine",3,10,15), it("hanging_leg_raise",2,8,15)]
plan("30_push_pull_legs_6day_gym.json","Push/Pull/Legs 6-Day (Gym)",
     "Each muscle twice a week over six sessions (Mon-Sat). High volume for experienced lifters; switch to rotation mode if you train fewer days.",
     [day(1,"Push",60,push), day(2,"Pull",60,pull), day(3,"Legs",65,legs),
      day(4,"Push 2",60,push), day(5,"Pull 2",60,pull), day(6,"Legs 2",65,legs)])

homeA = [it("goblet_squat",3,8,15), it("push_up",3,8,20), it("dumbbell_bent_over_row",3,8,12),
         it("dumbbell_romanian_deadlift",3,8,12), it("dumbbell_lateral_raises",2,12,20), it("plank_or_reverse_crunch",3,30,60)]
homeB = [it("dumbbell_reverse_lunges",3,8,12,True), it("seated_dumbbell_shoulder_press",3,8,12),
         it("single_arm_tripod_dumbbell_row",3,8,12,True), it("glute_bridge",3,10,20), it("supinated_dumbbell_bicep_curls",2,10,15),
         it("overhead_dumbbell_triceps_extension",2,10,15), it("dead_bugs",2,8,12)]
plan("40_home_dumbbell_3day.json","Home Dumbbells 3-Day",
     "Full body at home with a pair of adjustable dumbbells and a bench or chair (Mon/Wed/Fri), plus one easy cardio day.",
     [day(1,"Home A",45,homeA), day(3,"Home B",45,homeB), day(5,"Home A",45,homeA),
      day(6,"Easy Cardio",40,[it("zone2_cardio",1,30,45)],"CARDIO")])

plan("90_blank.json","Blank plan","Start from an empty week and add your own days and exercises.",
     [day(1,"Rest",0,[],"REST")])
print(len(lib), "library exercises; templates:", sorted(os.listdir(os.path.join(out,"templates"))))
