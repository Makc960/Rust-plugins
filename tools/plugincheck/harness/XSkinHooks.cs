using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using Oxide.Core.Libraries;

// Поведение горячих хуков XSkinMenu: одинаковые входы должны давать одинаковый результат
// до и после оптимизации. Наблюдаемое: item.skin (ветка SSI без пересоздания предмета).
public static class XSkinHooks
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;
    static Type XS; static object plugin; static int fails;

    static void Ok(string name, bool cond, string detail)
    {
        Console.WriteLine((cond ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (!cond) fails++;
    }
    static object F(string n) { return XS.GetField(n, Any).GetValue(plugin); }
    static object Get(object o, string n) { var f = o.GetType().GetField(n, Any); return f != null ? f.GetValue(o) : o.GetType().GetProperty(n, Any).GetValue(o); }
    static void Set(object o, string n, object v) { var f = o.GetType().GetField(n, Any); if (f != null) f.SetValue(o, v); else o.GetType().GetProperty(n, Any).SetValue(o, v); }
    static void Call(string m, params object[] a)
    {
        // Перегрузки (AddToBlacklist(ulong) / AddToBlacklist(List<ulong>)): берём ту, чьи параметры принимают аргументы.
        var mi = XS.GetMethods(Any).First(x => x.Name == m && x.GetParameters().Length >= a.Length &&
            x.GetParameters().Skip(a.Length).All(p => p.IsOptional) &&
            x.GetParameters().Take(a.Length).Select((p, i) => a[i] == null || p.ParameterType.IsInstanceOfType(a[i])).All(ok => ok));
        mi.Invoke(plugin, a.Concat(mi.GetParameters().Skip(a.Length).Select(p => p.DefaultValue)).ToArray());
    }

    static void Cmd(string command, string line)
    {
        ((IDictionary)F("Cooldowns")).Clear();
        var handler = XS.GetMethods(Any).First(m => m.GetCustomAttributes(typeof(Oxide.Plugins.ConsoleCommandAttribute), false).Cast<Oxide.Plugins.ConsoleCommandAttribute>().Any(a => a.Command == command));
        handler.Invoke(plugin, new object[] { new ConsoleSystem.Arg(player, line.Split(' ')) });
    }

    static BasePlayer player;
    static object Fresh(bool loaded, bool changeSG, bool changeSP, bool changeSC, ulong chosen)
    {
        plugin = Activator.CreateInstance(XS, true);
        Call("LoadDefaultConfig");
        Permission.Granted.Clear();
        foreach (var p in new[] { "xskinmenu.give", "xskinmenu.pickup", "xskinmenu.craft" }) Permission.Granted.Add(p);
        ((IDictionary)F("_items"))["rifle.ak"] = 0UL;
        ((IDictionary)F("StoredDataSkins"))["rifle.ak"] = new List<ulong> { chosen };
        Set(plugin, "StoredDataFriends", new Dictionary<ulong, bool>());
        player = new BasePlayer { userID = 76561198000000088UL, UserIDString = "76561198000000088", displayName = "H", IsConnected = true };
        player.inventory.containerMain.playerOwner = player;
        if (!loaded) return null;
        Call("LoadData", player);
        object data = ((IDictionary)F("StoredData"))[76561198000000088UL];
        Set(data, "ChangeSG", changeSG); Set(data, "ChangeSP", changeSP); Set(data, "ChangeSC", changeSC);
        ((IDictionary)Get(data, "Skins"))["rifle.ak"] = chosen;
        return data;
    }
    static Item Ak(ulong skin) { return new Item { info = new ItemDefinition { shortname = "rifle.ak", itemid = 1545779598 }, skin = skin, amount = 1, parent = player.inventory.containerMain }; }

    public static int Run()
    {
        XS = Type.GetType("Oxide.Plugins.XSkinMenu");

        // --- OnItemAddedToContainer ---
        Fresh(true, true, false, false, 777UL);
        var it = Ak(0);
        Call("OnItemAddedToContainer", player.inventory.containerMain, it);
        Ok("added: ChangeSG + выбран скин -> предмет перекрашен", it.skin == 777UL, "skin=" + it.skin);

        Fresh(true, false, false, false, 777UL); it = Ak(0);
        Call("OnItemAddedToContainer", player.inventory.containerMain, it);
        Ok("added: ChangeSG выкл -> без изменений", it.skin == 0UL, "skin=" + it.skin);

        Fresh(false, true, false, false, 777UL); it = Ak(0);
        Call("OnItemAddedToContainer", player.inventory.containerMain, it);
        Ok("added: данные игрока не загружены -> без изменений и без исключения", it.skin == 0UL, "skin=" + it.skin);

        Fresh(true, true, false, false, 777UL); Permission.Granted.Remove("xskinmenu.give"); it = Ak(0);
        Call("OnItemAddedToContainer", player.inventory.containerMain, it);
        Ok("added: нет права give -> без изменений", it.skin == 0UL, "skin=" + it.skin);

        Fresh(true, true, false, false, 777UL); it = Ak(777UL);
        Call("OnItemAddedToContainer", player.inventory.containerMain, it);
        Ok("added: скин уже нужный -> ничего не делает", it.skin == 777UL, "skin=" + it.skin);

        Fresh(true, true, false, false, 777UL); ((HashSet<ulong>)F("_removeATC")).Add(76561198000000088UL); it = Ak(0);
        Call("OnItemAddedToContainer", player.inventory.containerMain, it);
        Ok("added: игрок в _removeATC (получает кит) -> без изменений", it.skin == 0UL, "skin=" + it.skin);

        // --- OnItemPickup ---
        Fresh(true, false, true, false, 555UL); it = Ak(0);
        Call("OnItemPickup", it, player);
        Ok("pickup: ChangeSP + скин -> перекрашен", it.skin == 555UL, "skin=" + it.skin);

        Fresh(true, false, false, false, 555UL); it = Ak(0);
        Call("OnItemPickup", it, player);
        Ok("pickup: ChangeSP выкл -> без изменений", it.skin == 0UL, "skin=" + it.skin);

        Fresh(false, false, true, false, 555UL); it = Ak(0);
        Call("OnItemPickup", it, player);
        Ok("pickup: без данных -> без исключения", it.skin == 0UL, "skin=" + it.skin);

        // --- OnItemCraftFinished ---
        Fresh(true, false, false, true, 444UL); it = Ak(0);
        Call("OnItemCraftFinished", new ItemCraftTask { skinID = 0 }, it, new ItemCrafter { owner = player });
        Ok("craft: ChangeSC (и не ChangeSG) -> перекрашен", it.skin == 444UL, "skin=" + it.skin);

        Fresh(true, true, false, true, 444UL); it = Ak(0);
        Call("OnItemCraftFinished", new ItemCraftTask { skinID = 0 }, it, new ItemCrafter { owner = player });
        Ok("craft: ChangeSG вкл -> крафт не красит (это делает added)", it.skin == 0UL, "skin=" + it.skin);

        Fresh(true, false, false, true, 444UL); it = Ak(0);
        Call("OnItemCraftFinished", new ItemCraftTask { skinID = 123 }, it, new ItemCrafter { owner = player });
        Ok("craft: у задачи уже есть skinID -> без изменений", it.skin == 0UL, "skin=" + it.skin);

        Fresh(false, false, false, true, 444UL); it = Ak(0);
        bool threw = false;
        try { Call("OnItemCraftFinished", new ItemCraftTask { skinID = 0 }, it, new ItemCrafter { owner = player }); }
        catch (Exception) { threw = true; }
        Ok("craft: без данных -> не падает", !threw && it.skin == 0UL, threw ? "исключение" : "тихий выход");

        // --- чёрный список: зеркало синхронно с конфигом ---
        Fresh(true, true, false, false, 777UL);
        Call("AddToBlacklist", 999UL, "test");
        var blk = (HashSet<ulong>)F("_blacklist");
        var cfgBlk = (List<ulong>)Get(Get(F("config"), "Setting"), "Blacklist");
        Ok("blacklist: AddToBlacklist попадает и в конфиг, и в зеркало", blk.Contains(999UL) && cfgBlk.Contains(999UL), "оба содержат 999");
        it = Ak(999UL);
        Call("OnItemAddedToContainer", player.inventory.containerMain, it);
        Ok("blacklist: предмет с чёрным скином не перекрашивается", it.skin == 999UL, "skin=" + it.skin);

        // --- данные: запись на диск только для изменённых игроков ---
        const string UserFile = "XDataSystem/XSkinMenu/UserSettings/76561198000000088";
        const string FriendsFile = "XDataSystem/XSkinMenu/Friends";
        var disk = Oxide.Core.DataFileSystem.Store; var writes = Oxide.Core.DataFileSystem.Writes;
        Func<string, int> W = name => writes.Count(x => x == name);
        disk.Clear(); writes.Clear(); Timer.Scheduled.Clear();

        Fresh(true, true, false, false, 777UL);                                   // LoadData: файла нет -> данные новые
        Ok("data: LoadData без файла -> записи сразу нет", writes.Count == 0, "writes=" + writes.Count);
        Timer.Fire();                                                            // сработал отложенный сброс
        Ok("data: отложенный сброс пишет нового игрока и Friends по разу", W(UserFile) == 1 && W(FriendsFile) == 1 && writes.Count == 2, string.Join(",", writes));
        writes.Clear(); Timer.Fire(); Call("OnServerSave");
        Ok("data: без изменений OnServerSave/таймер не пишут ничего", writes.Count == 0, "writes=" + writes.Count);

        Permission.Granted.Add("xskinmenu.setting"); Cmd("skin_s", "inventory");             // переключатель в настройках
        Ok("data: клик в настройках -> запись отложена, не мгновенная", writes.Count == 0 && ((HashSet<ulong>)F("_dirty")).Contains(76561198000000088UL), "writes=" + writes.Count);
        Call("OnServerSave");
        Ok("data: OnServerSave пишет только изменённого игрока", W(UserFile) == 1 && writes.Count == 1, string.Join(",", writes));
        object saved = disk[UserFile];
        Ok("data: на диск ушёл тот же объект (формат прежний)", ReferenceEquals(saved, ((IDictionary)F("StoredData"))[76561198000000088UL]) && (bool)Get(saved, "ChangeSI") != (bool)Get(Get(F("config"), "PSetting"), "ChangeSI"), "ChangeSI переключён");
        writes.Clear(); Timer.Fire();
        Ok("data: повторный сброс после записи пуст", writes.Count == 0, "writes=" + writes.Count);

        Cmd("skin_s", "friends");
        writes.Clear(); Call("OnServerSave");
        Ok("data: переключение friends пишет только Friends", W(FriendsFile) == 1 && writes.Count == 1, string.Join(",", writes));

        writes.Clear(); Call("OnPlayerDisconnected", player);
        Ok("data: выход игрока пишет файл как раньше и снимает флаг", W(UserFile) == 1 && !((HashSet<ulong>)F("_dirty")).Contains(76561198000000088UL), string.Join(",", writes));

        writes.Clear(); Call("LoadData", player);                                // повторный вход: файл есть и полный
        Timer.Fire(); Call("OnServerSave");
        Ok("data: повторный вход с полным файлом -> не грязный, записи нет", writes.Count == 0, "writes=" + writes.Count);

        disk.Clear(); writes.Clear(); Timer.Scheduled.Clear();

        Console.WriteLine(fails == 0 ? "\nXSKIN HOOKS: ALL PASS" : "\nXSKIN HOOKS: " + fails + " FAILED");
        return fails;
    }
}
