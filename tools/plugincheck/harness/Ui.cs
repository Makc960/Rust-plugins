using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Reflection;
using Oxide.Game.Rust.Cui;

public static class Ui
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static |
                             BindingFlags.Public | BindingFlags.NonPublic;

    static Type AS, AccT, LayerT, WeaponT;
    static object plugin;
    static int fails;

    static void Ok(string name, bool cond, string detail)
    {
        Console.WriteLine((cond ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (!cond) fails++;
    }

    static object Get(object o, string n)
    {
        var f = o.GetType().GetField(n, Any);
        return f != null ? f.GetValue(o) : o.GetType().GetProperty(n, Any).GetValue(o);
    }
    static void Set(object o, string n, object v) { o.GetType().GetField(n, Any).SetValue(o, v); }
    static object Call(string m, params object[] a)
    {
        return AS.GetMethods(Any).First(x => x.Name == m).Invoke(plugin, a);
    }

    public static int Run()
    {
        AS = Type.GetType("Oxide.Plugins.AccountSystem");
        AccT = AS.GetNestedType("AccountData", BindingFlags.NonPublic);
        LayerT = AS.GetNestedType("StatLayer", BindingFlags.NonPublic);
        WeaponT = AS.GetNestedType("WeaponStat", BindingFlags.NonPublic);
        Type StoredT = AS.GetNestedType("StoredData", BindingFlags.NonPublic);
        Type ConfigT = AS.GetNestedType("ConfigData", BindingFlags.NonPublic);

        plugin = Activator.CreateInstance(AS, true);
        Set(plugin, "_data", Activator.CreateInstance(StoredT, true));
        Set(plugin, "_config", Activator.CreateInstance(ConfigT, true));

        object stored = Get(plugin, "_data");
        var accounts = (IDictionary)Get(stored, "Accounts");

        ulong me = 76561198000000010UL;
        var viewer = new BasePlayer { userID = me, displayName = "Tester", IsConnected = true };

        object acc = Activator.CreateInstance(AccT, true);
        Set(acc, "UserId", me);
        Set(acc, "Name", "Tester");
        Set(acc, "Level", 24);
        accounts[me] = acc;

        // --- наполняем профиль 5 000 событий ---
        foreach (string layerName in new[] { "Current", "Lifetime" })
        {
        object cur = Get(acc, layerName);
        var weapons = (IDictionary)Get(cur, "Weapons");
        var victims = (IDictionary)Get(cur, "KilledPlayers");
        var killers = (IDictionary)Get(cur, "KilledByPlayers");
        var resources = (IDictionary)Get(cur, "Resources");
        Type DuelT = AS.GetNestedType("DuelStat", BindingFlags.NonPublic);

        var rnd = new Random(7);
        for (int i = 0; i < 120; i++)
        {
            object w = Activator.CreateInstance(WeaponT, true);
            Set(w, "Kills", (long)rnd.Next(0, 90));
            Set(w, "Shots", (long)rnd.Next(100, 4000));
            Set(w, "Hits", (long)rnd.Next(50, 900));
            Set(w, "Headshots", (long)rnd.Next(0, 120));
            Set(w, "Damage", (double)rnd.Next(500, 90000));
            weapons[1000 + i] = w;
        }
        for (ulong i = 1; i <= 50; i++)
        {
            object d1 = Activator.CreateInstance(DuelT, true);
            Set(d1, "Count", (long)rnd.Next(1, 40)); Set(d1, "Name", "victim" + i);
            victims[70000000000000000UL + i] = d1;
            object d2 = Activator.CreateInstance(DuelT, true);
            Set(d2, "Count", (long)rnd.Next(1, 40)); Set(d2, "Name", "killer" + i);
            killers[71000000000000000UL + i] = d2;
        }
        for (int i = 0; i < 40; i++) resources["res." + i] = (long)rnd.Next(1000, 900000);

        Set(cur, "PlayerKills", 1450L);
        Set(cur, "Deaths", 720L);
        Set(cur, "ShotsFired", 54000L);
        Set(cur, "HitsLanded", 12000L);
        Set(cur, "Headshots", 1900L);
        Set(cur, "PlaySeconds", 360000L);
        Set(cur, "Sessions", 90);
        Set(cur, "LongestKillDistance", 214.7f);
        Set(cur, "LongestKillWeaponId", 1050);

        long[] days = (long[])Get(acc, "DayPlaySeconds");
        for (int i = 0; i < days.Length; i++) days[i] = rnd.Next(0, 28000);
        long[] hours = (long[])Get(cur, "HourMinutes");
        for (int i = 0; i < 24; i++) hours[i] = rnd.Next(0, 600);
        long[] causes = (long[])Get(cur, "DeathsByCause");
        for (int i = 0; i < causes.Length; i++) causes[i] = rnd.Next(0, 90);
        long[] grades = (long[])Get(cur, "BlocksByGrade");
        for (int i = 0; i < grades.Length; i++) grades[i] = rnd.Next(0, 400);

        var sessions = (IList)Get(acc, "RecentSessions");
        Type SessT = AS.GetNestedType("SessionRecord", BindingFlags.NonPublic);
        for (int i = 0; i < 10; i++)
        {
            object s = Activator.CreateInstance(SessT, true);
            Set(s, "StartedUtc", 1700000000L + i * 7200);
            Set(s, "EndedUtc", 1700000000L + i * 7200 + 3600);
            Set(s, "Seconds", 3600L);
            sessions.Add(s);
        }

        }

        object curLayer = Get(acc, "Current");
        var curWeapons = (IDictionary)Get(curLayer, "Weapons");
        long realEvents = 0;
        foreach (DictionaryEntry e in curWeapons)
            realEvents += (long)Get(e.Value, "Kills") + (long)Get(e.Value, "Shots") + (long)Get(e.Value, "Hits");
        foreach (DictionaryEntry e in (IDictionary)Get(curLayer, "KilledPlayers"))
            realEvents += (long)Get(e.Value, "Count");

        Console.WriteLine("  профиль: " + realEvents + " записанных событий, " + curWeapons.Count +
                          " видов оружия, " +
                          (((IDictionary)Get(curLayer, "KilledPlayers")).Count +
                           ((IDictionary)Get(curLayer, "KilledByPlayers")).Count) + " противников");

        // --- состояние вкладки ---
        Call("API_PrepareServerMenu", viewer, "");
        object view = ((IDictionary)Get(plugin, "_views"))[me];
        Ok("пустой payload открывает свой профиль",
           (ulong)Get(view, "Target") == me && !(bool)Get(view, "FromTop"), "Target=свой");

        Call("API_PrepareServerMenu", viewer, "76561198000000099");
        view = ((IDictionary)Get(plugin, "_views"))[me];
        Ok("payload SteamID64 открывает чужой профиль",
           (ulong)Get(view, "Target") == 76561198000000099UL && (bool)Get(view, "FromTop"),
           "Target=чужой, FromTop=true");

        Call("API_PrepareServerMenu", viewer, "не-число");
        view = ((IDictionary)Get(plugin, "_views"))[me];
        Ok("мусорный payload откатывается на свой профиль",
           (ulong)Get(view, "Target") == me, "Target=свой");

        Call("API_OnTabCommand", viewer, "sec weapons");
        Ok("команда меняет подвкладку", (string)Get(view, "Section") == "weapons",
           "Section=" + Get(view, "Section"));
        Call("API_OnTabCommand", viewer, "per lifetime");
        Ok("команда меняет период", (string)Get(view, "Period") == "lifetime",
           "Period=" + Get(view, "Period"));
        Call("API_OnTabCommand", viewer, "sec нет-такой");
        Ok("неизвестная подвкладка игнорируется", (string)Get(view, "Section") == "weapons",
           "Section=" + Get(view, "Section"));

        // --- отрисовка всех секций и периодов ---
        Set(view, "Target", me);
        Set(view, "FromTop", false);

        string[] sections = (string[])AS.GetField("SectionKeys", Any).GetValue(null);
        string[] periods = (string[])AS.GetField("PeriodKeys", Any).GetValue(null);
        var draw = AS.GetMethods(Any).First(x => x.Name == "DrawProfile");

        double worst = 0;
        string worstName = "";
        int worstJson = 0;

        foreach (string period in periods)
        {
            Set(view, "Period", period);
            foreach (string section in sections)
            {
                Set(view, "Section", section);
                draw.Invoke(plugin, new object[] { viewer, view, true });   // прогрев

                var sw = Stopwatch.StartNew();
                for (int i = 0; i < 20; i++)
                    draw.Invoke(plugin, new object[] { viewer, view, true });
                sw.Stop();

                double ms = sw.Elapsed.TotalMilliseconds / 20d;
                if (ms > worst) { worst = ms; worstName = period + "/" + section; worstJson = CuiHelper.LastJsonLength; }
            }
        }

        Ok("все 21 экран собираются", true,
           "самый тяжёлый " + worstName + " — " + worst.ToString("0.###") + " мс, " +
           CuiHelper.LastElementCount + " элементов");
        Ok("сборка экрана укладывается в миллисекунды", worst < 10d,
           worst.ToString("0.###") + " мс (порог 10 мс)");
        Ok("размер CUI разумный", worstJson < 400000,
           "JSON " + (worstJson / 1024) + " КБ");

        // --- пустой прошлый вайп показывает заглушку, а не голый экран ---
        Set(view, "Period", "previous");
        Set(view, "Section", "summary");
        draw.Invoke(plugin, new object[] { viewer, view, true });
        int prevCount = CuiHelper.LastElementCount;
        Set(view, "Period", "wipe");
        draw.Invoke(plugin, new object[] { viewer, view, true });
        Ok("пустой прошлый вайп рисует заглушку", prevCount < CuiHelper.LastElementCount,
           "прошлый=" + prevCount + " элементов против " + CuiHelper.LastElementCount);

        // --- перерисовка части окна ---
        // Одна и та же секция: с шапкой и без неё.
        Set(view, "Section", "pvp");
        draw.Invoke(plugin, new object[] { viewer, view, true });
        int fullCount = CuiHelper.LastElementCount;
        draw.Invoke(plugin, new object[] { viewer, view, false });
        int partialCount = CuiHelper.LastElementCount;
        Ok("перерисовка подвкладки не трогает шапку", partialCount < fullCount,
           "полная=" + fullCount + " элементов, частичная=" + partialCount +
           " (шапка " + (fullCount - partialCount) + " элементов остаётся)");

        Console.WriteLine(fails == 0 ? "\nUI: ALL PASS" : "\nUI: " + fails + " FAILED");
        return fails;
    }
}
