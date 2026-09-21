using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using Oxide.Core.Libraries;
using Oxide.Game.Rust.Cui;

// Снимает "транскрипт" каждого экрана XSkinMenu — последовательность DestroyUi/AddUi
// с полным JSON — для фиксированного состояния игрока и сравнивает с эталоном.
// Эталон записывается с версии автора; любое отличие после оптимизации = игрок увидит разницу.
public static class XSkinGolden
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;

    static Type XS;
    static object plugin;
    static BasePlayer player;

    class FakeImageLibrary : Oxide.Core.Plugins.Plugin
    {
        public bool HasImage(string name, ulong id) { return name.EndsWith("7"); }   // часть скинов "с картинкой"
        public string GetImage(string name, ulong id) { return "png-" + name; }
        public void AddImage(string url, string name, ulong id) { }
    }

    static object F(string name) { var f = XS.GetField(name, Any); return f.GetValue(plugin); }
    static void Set(object target, string name, object value)
    {
        var t = target.GetType();
        var f = t.GetField(name, Any);
        if (f != null) { f.SetValue(target, value); return; }
        t.GetProperty(name, Any).SetValue(target, value);
    }
    static object Get(object target, string name)
    {
        var t = target.GetType();
        var f = t.GetField(name, Any);
        return f != null ? f.GetValue(target) : t.GetProperty(name, Any).GetValue(target);
    }
    static void Call(string method, params object[] args)
    {
        var m = XS.GetMethods(Any).First(x => x.Name == method && x.GetParameters().Length >= args.Length &&
                                             x.GetParameters().Skip(args.Length).All(p => p.IsOptional));
        var full = args.Concat(m.GetParameters().Skip(args.Length).Select(p => p.DefaultValue)).ToArray();
        m.Invoke(plugin, full);
    }
    static void Cmd(string command, string line)
    {
        // сбрасываем кулдаун, как если бы прошло 1.5 с
        ((IDictionary)F("Cooldowns")).Clear();
        var handler = XS.GetMethods(Any).First(x => x.GetCustomAttributes(true)
            .OfType<Oxide.Plugins.ConsoleCommandAttribute>().Any(a => a.Command == command));
        handler.Invoke(plugin, new object[] { new ConsoleSystem.Arg(player, line.Split(' ')) });
    }

    static void Build(bool admin, bool vip, bool comfort, bool comfortp, bool imageLib)
    {
        plugin = Activator.CreateInstance(XS, true);
        Permission.Granted.Clear();
        foreach (var p in new[] { "xskinmenu.use", "xskinmenu.setting", "xskinmenu.skinitem", "xskinmenu.inventory",
                                  "xskinmenu.craft", "xskinmenu.entity", "xskinmenu.give", "xskinmenu.pickup",
                                  "xskinmenu.skincraft", "xskinmenu.playeradd", "xskinmenu.defaultkits", "xskinmenu.customkits" })
            Permission.Granted.Add(p);
        if (admin) { Permission.Granted.Add("xskinmenu.admin"); Permission.Granted.Add("xskinmenu.adminskins"); }
        if (vip) Permission.Granted.Add("xskinmenu.vipskins");

        Call("LoadDefaultConfig");
        object config = F("config");
        object setting = Get(config, "Setting");
        object gui = Get(config, "GUI");
        Set(setting, "UseImageLibrary", imageLib);
        Set(plugin, "ImageLibrary", imageLib ? new FakeImageLibrary { Name = "ImageLibrary", IsLoaded = true } : null);

        // категории и предметы - детерминированный набор
        var category = (IDictionary)Get(config, "Category");
        category.Clear();
        var weapons = new Dictionary<string, ulong>();
        var attire = new Dictionary<string, ulong>();
        string[] wep = { "rifle.ak", "rifle.lr300", "pistol.semiauto", "smg.thompson", "bow.hunting", "hatchet",
                         "pickaxe", "rocket.launcher", "shotgun.pump", "rifle.bolt", "knife.combat", "hammer" };
        string[] att = { "hoodie", "pants", "shoes.boots", "burlap.shirt", "mask.bandana", "hat.cap",
                         "jacket", "tshirt", "roadsign.jacket", "metal.facemask", "coffeecan.helmet" };
        foreach (var w in wep) weapons[w] = 0;
        foreach (var a in att) attire[a] = 0;
        category["Weapon"] = weapons;
        category["Attire"] = attire;

        var items = (IDictionary)F("_items"); var itemsId = (IDictionary)F("_itemsId");
        var skins = (IDictionary)F("StoredDataSkins"); var names = (IDictionary)F("StoredDataSkinsName");
        // Предметы из дефолтных китов конфига тоже должны быть известны плагину.
        string[] kitItems = { "burlap.gloves", "crossbow", "metal.plate.torso", "rifle.l96", "rifle.semiauto",
                              "roadsign.gloves", "roadsign.kilt", "smg.2", "smg.mp5" };
        int id = 1000;
        foreach (string key in wep.Concat(att).Concat(kitItems))
        {
            items[key] = 0UL; itemsId[key] = id++;
            var list = new List<ulong>();
            int n = key.Length * 7 % 90 + 5;          // 5..94 скинов, детерминированно
            int h = 0; foreach (char ch in key) h = (h * 31 + ch) % 1000;
            for (int i = 0; i < n; i++) { ulong s = (ulong)(100000 + h * 100 + i); list.Add(s); names[s] = key + " skin " + i; }
            skins[key] = list;
        }
        // admin/vip скины для rifle.ak
        var adminSkins = (IDictionary)Get(setting, "AdminSkins"); adminSkins.Clear();
        var vipSkins = (IDictionary)Get(setting, "VipSkins"); vipSkins.Clear();
        adminSkins["rifle.ak"] = new List<ulong> { 900001UL, 900002UL };
        vipSkins["rifle.ak"] = new List<ulong> { 900011UL };
        // Тип коллекции у плагина может меняться (List -> HashSet); harness'у важен только набор.
        foreach (ulong v in new[] { 900001UL, 900002UL }) ((ICollection<ulong>)F("_adminSkins")).Add(v);
        ((ICollection<ulong>)F("_vipSkins")).Add(900011UL);
        foreach (ulong v in new[] { 900001UL, 900002UL, 900011UL }) ((ICollection<ulong>)F("_adminAndVipSkins")).Add(v);
        names[900001UL] = "Admin A"; names[900002UL] = "Admin B"; names[900011UL] = "Vip A";

        // киты
        var kits = (IDictionary)Get(config, "KitsSetting");
        Set(plugin, "StoredDataFriends", new Dictionary<ulong, bool>());
        Set(plugin, "BgMainLayer", ".GUIS"); Set(plugin, "BgKitsLayer", ".KitsBGUI");

        player = new BasePlayer { userID = 76561198000000077UL, UserIDString = "76561198000000077", displayName = "Golden", IsConnected = true };
        Call("LoadData", player);
        object data = ((IDictionary)F("StoredData"))[76561198000000077UL];
        Set(data, "Comfort", comfort); Set(data, "ComfortP", comfortp);
        ((IDictionary)Get(data, "Skins"))["rifle.ak"] = 100005UL;   // выбранный скин
        var playerKits = (IDictionary)Get(data, "Kits");
        playerKits["MyKit"] = new Dictionary<string, ulong> { ["rifle.ak"] = 100005UL, ["hoodie"] = 0UL };
    }

    static List<string> Snap(string label, Action act)
    {
        CuiHelper.Transcript.Clear();
        CuiHelper.GuidCounter = 0;
        act();
        var lines = new List<string> { "### " + label };
        lines.AddRange(CuiHelper.Transcript);
        return lines;
    }

    public static int Run(string mode, string path)
    {
        XS = Type.GetType("Oxide.Plugins.XSkinMenu");
        var all = new List<string>();

        foreach (bool imageLib in new[] { false, true })
        foreach (bool admin in new[] { false, true })
        foreach (bool comfort in new[] { false, true })
        foreach (bool comfortp in new[] { false, true })
        {
            if (comfortp && !comfort) continue;
            string st = $"img={imageLib} admin={admin} vip={admin} comfort={comfort} comfortp={comfortp}";
            Build(admin, admin, comfort, comfortp, imageLib);

            all.AddRange(Snap(st + " | GUI", () => Call("GUI", player)));
            all.AddRange(Snap(st + " | CategoryGUI 0", () => Call("CategoryGUI", player, 0)));
            all.AddRange(Snap(st + " | ItemGUI Weapon p0", () => Call("ItemGUI", player, "Weapon", 0, "null")));
            all.AddRange(Snap(st + " | ItemGUI Weapon p0 sel", () => Call("ItemGUI", player, "Weapon", 0, "rifle.ak")));
            all.AddRange(Snap(st + " | ItemGUI Attire p1", () => Call("ItemGUI", player, "Attire", 1, "null")));
            all.AddRange(Snap(st + " | SkinGUI ak p0", () => Call("SkinGUI", player, "rifle.ak", 0, "Weapon", 0, "")));
            all.AddRange(Snap(st + " | SkinGUI ak p1", () => Call("SkinGUI", player, "rifle.ak", 1, "Weapon", 0, "")));
            all.AddRange(Snap(st + " | SkinGUI ak search", () => Call("SkinGUI", player, "rifle.ak", 0, "Weapon", 0, "skin 1")));
            all.AddRange(Snap(st + " | SkinGUI hoodie", () => Call("SkinGUI", player, "hoodie", 0, "Attire", 0, "")));
            all.AddRange(Snap(st + " | SettingGUI", () => Call("SettingGUI", player)));
            all.AddRange(Snap(st + " | SetItemGUI ak", () => Call("SetItemGUI", player, "rifle.ak", 0, false, "")));
            all.AddRange(Snap(st + " | SetItemGUI ak entity", () => Call("SetItemGUI", player, "rifle.ak", 0, true, "")));
            all.AddRange(Snap(st + " | ZoomGUI", () => Call("ZoomGUI", player, 1000, 100005UL, false)));
            all.AddRange(Snap(st + " | DefaultKitsGUI", () => Call("DefaultKitsGUI", player, 0)));
            all.AddRange(Snap(st + " | CustomKitsGUI", () => Call("CustomKitsGUI", player, 0)));
            all.AddRange(Snap(st + " | CreateKitGUI", () => Call("CreateKitGUI", player)));
            all.AddRange(Snap(st + " | SkinKitsGUI", () => Call("SkinKitsGUI", player, false)));
            all.AddRange(Snap(st + " | SendInfo", () => Call("SendInfo", player, "hello")));

            // Реальные клики через обработчики команд — самый важный путь.
            all.AddRange(Snap(st + " | click category", () => Cmd("skin_c", "category Attire 0")));
            all.AddRange(Snap(st + " | click item", () => Cmd("skin_c", comfort ? "skin rifle.ak Weapon 0" : "skin rifle.ak")));
            all.AddRange(Snap(st + " | click setskin", () => Cmd("skin_c", comfort ? "setskin rifle.ak 100007 0 Weapon 0" : "setskin rifle.ak 100007 0")));
            all.AddRange(Snap(st + " | click setskin back", () => Cmd("skin_c", comfort ? "setskin rifle.ak 100005 0 Weapon 0" : "setskin rifle.ak 100005 0")));
            all.AddRange(Snap(st + " | page skin next", () => Cmd("page.xskinmenu", "skin rifle.ak 1 Weapon 0")));
            all.AddRange(Snap(st + " | page item next", () => Cmd("page.xskinmenu", "item Weapon 1 null")));
            all.AddRange(Snap(st + " | click clear", () => Cmd("skin_c", "clear rifle.ak rifle.ak Weapon 0")));
            all.AddRange(Snap(st + " | click zoom", () => Cmd("skin_c", "zoomskin 1000 100005 false")));
            all.AddRange(Snap(st + " | click openkit", () => Cmd("skin_c", "openkit")));
        }

        if (mode == "record")
        {
            File.WriteAllLines(path, all);
            Console.WriteLine("эталон записан: " + all.Count(l => l.StartsWith("### ")) + " экранов, " +
                              all.Count(l => l.StartsWith("A ")) + " AddUi, " + all.Count(l => l.StartsWith("D ")) + " DestroyUi, " +
                              (new FileInfo(path).Length / 1024) + " КБ");
            return 0;
        }

        var golden = File.ReadAllLines(path);
        int screens = 0, mismatched = 0; string current = "";
        int max = Math.Max(golden.Length, all.Count);
        for (int i = 0; i < max; i++)
        {
            string g = i < golden.Length ? golden[i] : "<нет>";
            string a = i < all.Count ? all[i] : "<нет>";
            if (g.StartsWith("### ")) { screens++; current = g; }
            if (g == a) continue;
            mismatched++;
            if (mismatched <= 5)
            {
                Console.WriteLine("  РАСХОЖДЕНИЕ в " + current + " (строка " + i + ")");
                int k = 0; while (k < g.Length && k < a.Length && g[k] == a[k]) k++;
                Console.WriteLine("    эталон: …" + g.Substring(Math.Max(0, k - 60), Math.Min(140, g.Length - Math.Max(0, k - 60))));
                Console.WriteLine("    сейчас: …" + a.Substring(Math.Max(0, k - 60), Math.Min(140, a.Length - Math.Max(0, k - 60))));
            }
        }
        Console.WriteLine(mismatched == 0
            ? "  PASS  CUI побайтно совпадает с эталоном: " + screens + " экранов, " + all.Count + " строк транскрипта"
            : "  FAIL  расхождений: " + mismatched + " из " + max + " строк");
        return mismatched == 0 ? 0 : 1;
    }
}
