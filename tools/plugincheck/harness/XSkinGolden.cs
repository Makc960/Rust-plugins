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
                                  "xskinmenu.skincraft", "xskinmenu.playeradd", "xskinmenu.defaultkits", "xskinmenu.customkits", "xskinmenu.skinchange" })
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
        Set(data, "ChangeSI", false); Set(data, "ChangeSCL", false);   // перекраска инвентаря - не UI, в заглушке нет ItemManager
        ((IDictionary)Get(data, "Skins"))["rifle.ak"] = 104005UL;   // выбранный скин (список rifle.ak: 104000..104060)
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

    static List<Tuple<string, List<string>>> Split(IEnumerable<string> lines)
    {
        var res = new List<Tuple<string, List<string>>>();
        foreach (var l in lines)
        {
            if (l.StartsWith("### ")) res.Add(Tuple.Create(l, new List<string>()));
            else res[res.Count - 1].Item2.Add(l);
        }
        return res;
    }

    static void Report(string screen, string what, string g, string a, ref int mismatched)
    {
        mismatched++;
        if (mismatched > 5) return;
        Console.WriteLine("  РАСХОЖДЕНИЕ в " + screen + " (" + what + ")");
        int k = 0; while (k < g.Length && k < a.Length && g[k] == a[k]) k++;
        Console.WriteLine("    эталон: …" + g.Substring(Math.Max(0, k - 60), Math.Min(140, g.Length - Math.Max(0, k - 60))));
        Console.WriteLine("    сейчас: …" + a.Substring(Math.Max(0, k - 60), Math.Min(140, a.Length - Math.Max(0, k - 60))));
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
            // Выбор скина перерисовывает не всю сетку, поэтому экраны "click setskin*" сравниваются по итоговому состоянию.
            all.AddRange(Snap(st + " | click setskin", () => Cmd("skin_c", comfort ? "setskin rifle.ak 104007 0 Weapon 0" : "setskin rifle.ak 104007 0")));
            all.AddRange(Snap(st + " | click setskin back", () => Cmd("skin_c", comfort ? "setskin rifle.ak 104005 0 Weapon 0" : "setskin rifle.ak 104005 0")));
            all.AddRange(Snap(st + " | click setskin same", () => Cmd("skin_c", comfort ? "setskin rifle.ak 104005 0 Weapon 0" : "setskin rifle.ak 104005 0")));
            all.AddRange(Snap(st + " | page skin next", () => Cmd("page.xskinmenu", "skin rifle.ak 1 Weapon 0")));
            all.AddRange(Snap(st + " | click setskin p1 (old on p0)", () => Cmd("skin_c", comfort ? "setskin rifle.ak 104045 1 Weapon 0" : "setskin rifle.ak 104045 1")));
            // поиск через реальную команду поля ввода: в обычном режиме категория в сетке всегда "null", как её рисуют клики
            all.AddRange(Snap(st + " | click search", () => Cmd("skin_c", comfort ? "searchskin rifle.ak Weapon 0 skin 1" : "searchskin rifle.ak null skin 1")));
            all.AddRange(Snap(st + " | click setskin in search", () => Cmd("skin_c", comfort ? "setskin rifle.ak 104012 0 Weapon 0 skin 1" : "setskin rifle.ak 104012 0 skin 1")));
            all.AddRange(Snap(st + " | click item again", () => Cmd("skin_c", comfort ? "skin rifle.ak Weapon 0" : "skin rifle.ak")));
            all.AddRange(Snap(st + " | click setskin admin skin", () => Cmd("skin_c", comfort ? "setskin rifle.ak 900001 0 Weapon 0" : "setskin rifle.ak 900001 0")));
            all.AddRange(Snap(st + " | click setskin from admin", () => Cmd("skin_c", comfort ? "setskin rifle.ak 104003 0 Weapon 0" : "setskin rifle.ak 104003 0")));
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
        var gs = Split(golden); var cs = Split(all);
        int screens = 0, byState = 0, mismatched = 0; string cell = null;
        var vg = new XSkinScreen(); var vc = new XSkinScreen();
        if (gs.Count != cs.Count) { mismatched++; Console.WriteLine("  РАСХОЖДЕНИЕ: экранов в эталоне " + gs.Count + ", сейчас " + cs.Count); }
        for (int n = 0; n < Math.Min(gs.Count, cs.Count); n++)
        {
            var g = gs[n]; var c = cs[n];
            screens++;
            if (g.Item1 != c.Item1) { mismatched++; Report(g.Item1, "метка экрана", g.Item1, c.Item1, ref mismatched); continue; }
            string cellNow = g.Item1.Split('|')[0];
            if (cellNow != cell) { cell = cellNow; vg = new XSkinScreen(); vc = new XSkinScreen(); }
            foreach (var l in g.Item2) vg.Apply(l);
            foreach (var l in c.Item2) vc.Apply(l);
            if (g.Item1.Contains("click setskin"))
            {
                // частичная перерисовка: сравниваем не команды, а что в итоге на экране
                byState++;
                string a = vg.Canonical(), b = vc.Canonical();
                if (a != b) Report(g.Item1, "состояние экрана", a, b, ref mismatched);
                continue;
            }
            int max = Math.Max(g.Item2.Count, c.Item2.Count);
            for (int i = 0; i < max; i++)
            {
                string a = i < g.Item2.Count ? XSkinScreen.NormLine(g.Item2[i]) : "<нет>";
                string b = i < c.Item2.Count ? XSkinScreen.NormLine(c.Item2[i]) : "<нет>";
                if (a != b) Report(g.Item1, "строка " + i, a, b, ref mismatched);
            }
        }
        Console.WriteLine(mismatched == 0
            ? "  PASS  CUI совпадает с эталоном: " + screens + " экранов, из них " + (screens - byState) + " побайтно (имена плиток нормализованы) и " + byState + " по итоговому состоянию экрана"
            : "  FAIL  расхождений: " + mismatched);
        return mismatched == 0 ? 0 : 1;
    }
}
