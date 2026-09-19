using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

public static class Behaviour
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static |
                             BindingFlags.Public | BindingFlags.NonPublic;

    static Type AS, AccT, LayerT;
    static object plugin;
    static int fails;

    static void Ok(string name, bool cond, string detail)
    {
        Console.WriteLine((cond ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (!cond) fails++;
    }

    static object Get(object o, string name)
    {
        var t = o.GetType();
        var f = t.GetField(name, Any);
        if (f != null) return f.GetValue(o);
        return t.GetProperty(name, Any).GetValue(o);
    }

    static void Set(object o, string name, object value)
    {
        o.GetType().GetField(name, Any).SetValue(o, value);
    }

    static object Invoke(string method, params object[] args)
    {
        var m = AS.GetMethods(Any).First(x => x.Name == method);
        return m.Invoke(plugin, args);
    }

    static object NewAccount(ulong id)
    {
        object a = Activator.CreateInstance(AccT, true);
        Set(a, "UserId", id);
        return a;
    }

    public static int Run()
    {
        AS = Type.GetType("Oxide.Plugins.AccountSystem");
        AccT = AS.GetNestedType("AccountData", BindingFlags.NonPublic);
        LayerT = AS.GetNestedType("StatLayer", BindingFlags.NonPublic);
        Type StoredT = AS.GetNestedType("StoredData", BindingFlags.NonPublic);

        plugin = Activator.CreateInstance(AS, true);
        object stored = Activator.CreateInstance(StoredT, true);
        Set(plugin, "_data", stored);

        var accounts = (IDictionary)Get(stored, "Accounts");
        object acc = NewAccount(76561198000000002UL);
        accounts[76561198000000002UL] = acc;

        // --- OnNewSave: сдвиг слоёв ---
        Set(Get(acc, "Current"), "PlayerKills", 33L);
        Set(Get(acc, "Lifetime"), "PlayerKills", 333L);
        Set(stored, "CurrentWipeId", "wipe-A");

        Invoke("OnNewSave", "wipe-B");

        Ok("вайп: текущий стал прошлым", (long)Get(Get(acc, "Previous"), "PlayerKills") == 33,
           "Previous.PlayerKills=" + Get(Get(acc, "Previous"), "PlayerKills"));
        Ok("вайп: текущий обнулён", (long)Get(Get(acc, "Current"), "PlayerKills") == 0,
           "Current.PlayerKills=" + Get(Get(acc, "Current"), "PlayerKills"));
        Ok("вайп: «всё время» не тронуто", (long)Get(Get(acc, "Lifetime"), "PlayerKills") == 333,
           "Lifetime.PlayerKills=" + Get(Get(acc, "Lifetime"), "PlayerKills"));
        Ok("вайп: id прошлого вайпа запомнен", (string)Get(stored, "PreviousWipeId") == "wipe-A",
           "PreviousWipeId=" + Get(stored, "PreviousWipeId"));

        // --- активность: сдвиг дней ---
        object acc2 = NewAccount(76561198000000003UL);
        accounts[76561198000000003UL] = acc2;
        long[] days = (long[])Get(acc2, "DayPlaySeconds");
        Set(acc2, "DayAnchorUtc", 1000L);
        days[29] = 500;   // «сегодня»
        days[28] = 400;
        Invoke("ShiftActivityDays", acc2, 1003L * 86400L);
        days = (long[])Get(acc2, "DayPlaySeconds");
        Ok("дни сдвинулись на 3 суток", days[26] == 500 && days[25] == 400 && days[29] == 0,
           "d26=" + days[26] + " d25=" + days[25] + " d29=" + days[29]);

        long[] days2 = (long[])Get(acc2, "DayPlaySeconds");
        Invoke("ShiftActivityDays", acc2, 2000L * 86400L);
        Ok("длинный перерыв очищает окно", days2.All(x => x == 0), "все 30 дней пусты");

        // --- активность: часы по границам ---
        object acc3 = NewAccount(76561198000000004UL);
        accounts[76561198000000004UL] = acc3;
        // 01:50 UTC + 20 минут -> 10 мин в час 1, 10 мин в час 2
        long start = 1700000000L / 86400L * 86400L + 1 * 3600 + 50 * 60;
        Invoke("AddActivity", acc3, start, start + 20 * 60, 20L * 60L);
        long[] hours = (long[])Get(Get(acc3, "Current"), "HourMinutes");
        Ok("часы разложены по границе", hours[1] == 10 && hours[2] == 10,
           "h1=" + hours[1] + " h2=" + hours[2]);

        // --- сессии: лимит 10 ---
        for (int i = 0; i < 15; i++)
            Invoke("PushSession", acc3, 1000L + i * 100, 1000L + i * 100 + 60);
        var sessions = (IList)Get(acc3, "RecentSessions");
        Ok("хранится 10 последних сессий", sessions.Count == 10, "сессий=" + sessions.Count);

        // --- любимое оружие: по убийствам, при равенстве по урону ---
        object layer = Activator.CreateInstance(LayerT, true);
        Type WeaponT = AS.GetNestedType("WeaponStat", BindingFlags.NonPublic);
        var weapons = (IDictionary)Get(layer, "Weapons");

        object w1 = Activator.CreateInstance(WeaponT, true);
        Set(w1, "Kills", 5L); Set(w1, "Damage", 100d);
        object w2 = Activator.CreateInstance(WeaponT, true);
        Set(w2, "Kills", 5L); Set(w2, "Damage", 900d);
        object w3 = Activator.CreateInstance(WeaponT, true);
        Set(w3, "Kills", 2L); Set(w3, "Damage", 5000d);
        weapons[111] = w1; weapons[222] = w2; weapons[333] = w3;

        var fav = AS.GetMethod("FavouriteWeapon", Any);
        int favId = (int)fav.Invoke(null, new object[] { layer });
        Ok("любимое оружие: равные убийства решает урон", favId == 222, "itemid=" + favId);

        // --- вытеснение оружия по лимиту ---
        object layer2 = Activator.CreateInstance(LayerT, true);
        var slot = AS.GetMethod("WeaponSlot", Any);
        for (int i = 1; i <= 130; i++)
        {
            object st = slot.Invoke(null, new object[] { layer2, i });
            Set(st, "Kills", (long)i);
        }
        var map2 = (IDictionary)Get(layer2, "Weapons");
        Ok("оружие не превышает потолок", map2.Count <= 120, "записей=" + map2.Count);
        Ok("вытеснено самое слабое", !map2.Contains(1) && map2.Contains(130),
           "есть #130, нет #1");

        // --- вытеснение «кто кого» по лимиту 50 ---
        object layer3 = Activator.CreateInstance(LayerT, true);
        var duels = (IDictionary)Get(layer3, "KilledPlayers");
        var bump = AS.GetMethod("BumpDuel", Any);
        for (ulong i = 1; i <= 60; i++)
            for (int k = 0; k < (int)i; k++)
                bump.Invoke(null, new object[] { duels, i, "p" + i });
        Ok("«кто кого» не превышает 50", duels.Count <= 50, "записей=" + duels.Count);
        Ok("вытеснены редкие противники", !duels.Contains(1UL) && duels.Contains(60UL),
           "есть #60, нет #1");

        Console.WriteLine(fails == 0 ? "\nBEHAVIOUR: ALL PASS" : "\nBEHAVIOUR: " + fails + " FAILED");
        return fails;
    }
}
