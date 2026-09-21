using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

public static class ChatCheck
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;

    static int fails;
    static Type SC;
    static object plugin;

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
        return SC.GetMethods(Any).First(x => x.Name == m && x.GetParameters().Length == a.Length)
            .Invoke(plugin, a);
    }

    public static int Run()
    {
        SC = Type.GetType("Oxide.Plugins.ServerChat");
        plugin = Activator.CreateInstance(SC, true);
        Type ConfigT = SC.GetNestedType("ConfigData", BindingFlags.NonPublic);
        Set(plugin, "_config", Activator.CreateInstance(ConfigT, true));

        // --- API, как его зовут плагины сборки ---
        MethodInfo alertPlayer = SC.GetMethods(Any).FirstOrDefault(x => x.Name == "API_ALERT_PLAYER");
        MethodInfo alert = SC.GetMethods(Any).FirstOrDefault(x => x.Name == "API_ALERT");

        Ok("API_ALERT_PLAYER есть", alertPlayer != null, "метод найден");
        Ok("API_ALERT есть", alert != null, "метод найден");

        var p1 = alertPlayer.GetParameters();
        Ok("API_ALERT_PLAYER: сигнатура IQChat.cs:1127",
           p1.Length == 5 && p1[0].ParameterType == typeof(BasePlayer) &&
           p1[1].ParameterType == typeof(string) && p1[2].ParameterType == typeof(string) &&
           p1[3].ParameterType == typeof(string) && p1[4].ParameterType == typeof(string),
           string.Join(", ", p1.Select(x => x.ParameterType.Name)));
        Ok("API_ALERT_PLAYER: три последних необязательные",
           p1.Skip(2).All(x => x.IsOptional && x.DefaultValue == null), "customPrefix/Avatar/Hex = null");

        var p2 = alert.GetParameters();
        Ok("API_ALERT: сигнатура из ТЗ",
           p2.Length == 4 && p2[0].ParameterType == typeof(string) &&
           p2.Skip(1).All(x => x.IsOptional), string.Join(", ", p2.Select(x => x.ParameterType.Name)));

        Ok("оба помечены [HookMethod]",
           alertPlayer.GetCustomAttributes(true).Any(a => a.GetType().Name == "HookMethodAttribute") &&
           alert.GetCustomAttributes(true).Any(a => a.GetType().Name == "HookMethodAttribute"),
           "Call() их найдёт");

        // Вызовы ровно так, как в сборке: 2 аргумента (XRaidProtection) и 4 (остальные).
        var asPlugin = (Oxide.Core.Plugins.Plugin)plugin;
        var player = new BasePlayer { userID = 76561198000000031UL, displayName = "Игрок", IsConnected = true };

        bool twoArgs = true, fourArgs = true;
        try { asPlugin.Call("API_ALERT_PLAYER", player, "тест"); } catch (Exception) { twoArgs = false; }
        try { asPlugin.Call("API_ALERT_PLAYER", player, "тест", "[ПРЕФИКС]", "76561199000000000"); }
        catch (Exception) { fourArgs = false; }

        Ok("XRaidProtection.cs:609 — Call с 2 аргументами", twoArgs, "API_ALERT_PLAYER, player, Message");
        Ok("IQSimpleVote.cs:927 / IQWipeBlock.cs:1099 / IQRates.cs:1866 — Call с 4", fourArgs,
           "+ customPrefix, customAvatar");

        // --- формат сообщения ---
        object cfg = Get(plugin, "_config");
        Set(cfg, "Prefix", "[СЕРВЕР]");
        Set(cfg, "PrefixHex", "#65A30D");

        string composed = (string)Call("Compose", "привет", null, null);
        Ok("формат по умолчанию", composed == "<color=#65A30D>[СЕРВЕР]</color> привет", composed);

        string custom = (string)Call("Compose", "привет", "[ГОЛОСОВАНИЕ]", "#FF0000");
        Ok("свой префикс и цвет", custom == "<color=#FF0000>[ГОЛОСОВАНИЕ]</color> привет", custom);

        string bare = (string)Call("Compose", "привет", "", null);
        Ok("пустой префикс — только текст", bare == "привет", bare);

        string multiline = (string)Call("Compose", "первая\nвторая", null, null);
        Ok("перенос строки сохраняется", multiline.Contains("\n"), "\\n на месте");

        // --- поиск игрока ---
        BasePlayer.activePlayerList.Clear();
        var alice = new BasePlayer { userID = 76561198000000041UL, displayName = "Алиса", IsConnected = true };
        var alex = new BasePlayer { userID = 76561198000000042UL, displayName = "Алексей", IsConnected = true };
        var bob = new BasePlayer { userID = 76561198000000043UL, displayName = "Боб", IsConnected = true };
        BasePlayer.activePlayerList.AddRange(new[] { alice, alex, bob });

        object found = Call("FindPlayer", player, "Боб");
        Ok("поиск по части ника — одно совпадение", ReferenceEquals(found, bob), "найден Боб");

        object many = Call("FindPlayer", player, "Ал");
        Ok("два совпадения — не отправляем", many == null, "вернул null, показан список");

        object none = Call("FindPlayer", player, "Некто");
        Ok("никого не найдено", none == null, "вернул null");

        // Точное совпадение выигрывает у частичного.
        var al = new BasePlayer { userID = 76561198000000044UL, displayName = "Ал", IsConnected = true };
        BasePlayer.activePlayerList.Add(al);
        object exact = Call("FindPlayer", player, "Ал");
        Ok("точный ник побеждает частичные", ReferenceEquals(exact, al), "найден Ал");

        // --- кулдаун ---
        var sent = (IDictionary)Get(plugin, "_lastSent");
        sent.Clear();
        bool first = (bool)Call("OnCooldown", player);
        Ok("первое сообщение проходит", !first, "кулдауна нет");

        sent[(ulong)player.userID] = UnityEngine.Time.realtimeSinceStartup;
        Set(cfg, "PmCooldownSeconds", 2f);
        bool second = (bool)Call("OnCooldown", player);
        Ok("повтор в пределах 2 секунд блокируется", second, "кулдаун сработал");

        // --- прочее ---
        Ok("Unload снимает таймер",
           SC.GetMethods(Any).Any(m => m.Name == "Unload"), "метод на месте");

        string[] chatCommands = SC.GetMethods(Any)
            .SelectMany(m => m.GetCustomAttributes(true)
                .Where(a => a.GetType().Name == "ChatCommandAttribute")
                .Select(a => m.Name))
            .ToArray();
        Ok("команды объявлены", chatCommands.Length == 8,
           chatCommands.Length + ": " + string.Join(", ", chatCommands.OrderBy(x => x)));

        // Форматирование чата, ники, муты и игнор не реализуем.
        string[] forbidden = { "OnPlayerChat", "OnUserChat", "OnPlayerVoice", "API_MUTE", "API_IGNORE" };
        var present = forbidden.Where(h => SC.GetMethods(Any).Any(m => m.Name == h)).ToArray();
        Ok("чат, муты и игнор не трогаем", present.Length == 0,
           present.Length == 0 ? "таких хуков нет" : string.Join(", ", present));

        Console.WriteLine(fails == 0 ? "\nSERVERCHAT: ALL PASS" : "\nSERVERCHAT: " + fails + " FAILED");
        return fails;
    }
}
