using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using Oxide.Core;
using Oxide.Game.Rust.Cui;

public static class Integration
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static |
                             BindingFlags.Public | BindingFlags.NonPublic;

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
    static void Set(object o, string n, object v)
    {
        var f = o.GetType().GetField(n, Any);
        if (f != null) { f.SetValue(o, v); return; }
        o.GetType().GetProperty(n, Any).SetValue(o, v);
    }
    static object Call(object target, string m, params object[] a)
    {
        return target.GetType().GetMethods(Any).First(x => x.Name == m && x.GetParameters().Length == a.Length)
            .Invoke(target, a);
    }

    public static int Run()
    {
        Type SM = Type.GetType("Oxide.Plugins.ServerMenu");
        Type AS = Type.GetType("Oxide.Plugins.AccountSystem");

        // --- профиль больше не рисуется в ServerMenu ---
        Ok("DrawAccountProfile удалён из ServerMenu",
           SM.GetMethods(Any).All(x => x.Name != "DrawAccountProfile"), "метода нет");
        Ok("SelectedAccountId удалён из состояния",
           SM.GetNestedType("MenuState", BindingFlags.NonPublic)
             .GetFields(Any).All(x => x.Name != "SelectedAccountId"), "поля нет");

        var isBuiltIn = SM.GetMethod("IsBuiltInTab", Any);
        Ok("account больше не встроенная вкладка",
           !(bool)isBuiltIn.Invoke(null, new object[] { "account" }), "IsBuiltInTab(account)=false");
        Ok("остальные встроенные вкладки на месте",
           (bool)isBuiltIn.Invoke(null, new object[] { "home" }) &&
           (bool)isBuiltIn.Invoke(null, new object[] { "top" }) &&
           (bool)isBuiltIn.Invoke(null, new object[] { "services" }) &&
           (bool)isBuiltIn.Invoke(null, new object[] { "commands" }), "home/top/services/commands");

        // --- поднимаем оба плагина и связываем, как Oxide ---
        object menu = Activator.CreateInstance(SM, true);
        object account = Activator.CreateInstance(AS, true);
        Set(menu, "Name", "ServerMenu");
        Set(menu, "IsLoaded", true);
        Set(account, "Name", "AccountSystem");
        Set(account, "IsLoaded", true);

        Interface.Oxide.RootPluginManager.Plugins["ServerMenu"] = (Oxide.Core.Plugins.Plugin)menu;
        Interface.Oxide.RootPluginManager.Plugins["AccountSystem"] = (Oxide.Core.Plugins.Plugin)account;

        Type StoredT = AS.GetNestedType("StoredData", BindingFlags.NonPublic);
        Type ConfigT = AS.GetNestedType("ConfigData", BindingFlags.NonPublic);
        Type AccT = AS.GetNestedType("AccountData", BindingFlags.NonPublic);
        Set(account, "_data", Activator.CreateInstance(StoredT, true));
        Set(account, "_config", Activator.CreateInstance(ConfigT, true));
        Set(account, "ServerMenu", (Oxide.Core.Plugins.Plugin)menu);

        ulong me = 76561198000000021UL;
        var player = new BasePlayer { userID = me, displayName = "Игрок", IsConnected = true };

        object acc = Activator.CreateInstance(AccT, true);
        Set(acc, "UserId", me);
        Set(acc, "Name", "Игрок");
        ((IDictionary)Get(Get(account, "_data"), "Accounts"))[me] = acc;

        // --- регистрация вкладки ---
        Call(account, "RegisterMenuTab");
        var extTabs = (IDictionary)Get(menu, "extTabs");
        Ok("AccountSystem зарегистрировал вкладку", extTabs.Contains("account"),
           "extTabs содержит account");

        var isAllowed = SM.GetMethod("IsAllowedTab", Any);
        Ok("вкладка стала разрешённой", (bool)isAllowed.Invoke(menu, new object[] { player, "account" }),
           "IsAllowedTab(account)=true");

        // --- вкладка появилась в полосе /menu ---
        var strip = (IList)SM.GetMethod("TabStrip", Any).Invoke(menu, new object[] { player });
        bool inStrip = false;
        foreach (object entry in strip)
            if ((string)Get(entry, "Key") == "account") inStrip = true;
        Ok("вкладка профиля видна в /menu", inStrip, "в полосе " + strip.Count + " вкладок");

        // --- открытие чужого профиля через payload ---
        var states = (IDictionary)Get(menu, "states");
        Call(menu, "OpenIntegrated", player, "account", "76561198000000099");
        object state = states[me];
        Ok("вкладка открылась", (string)Get(state, "Tab") == "account", "Tab=" + Get(state, "Tab"));

        object view = ((IDictionary)Get(account, "_views"))[me];
        Ok("payload доехал до AccountSystem",
           (ulong)Get(view, "Target") == 76561198000000099UL && (bool)Get(view, "FromTop"),
           "Target=" + Get(view, "Target") + " FromTop=true");

        // Цели нет в данных - профиль должен показать заглушку, а не пустоту.
        Call(menu, "Draw", player, state);
        Ok("несуществующий аккаунт рисует заглушку", CuiHelper.LastElementCount > 0,
           CuiHelper.LastElementCount + " элементов");

        // Свой профиль: полноценная отрисовка через ServerMenu.
        Call(menu, "OpenIntegrated", player, "account", "");
        state = states[me];
        Call(menu, "Draw", player, state);
        Ok("свой профиль отрисован целиком", CuiHelper.LastElementCount > 40,
           CuiHelper.LastElementCount + " элементов");

        // --- возврат к топу ---
        Call(menu, "Open", player, "top");
        Ok("кнопка «назад» возвращает в топ", (string)Get(states[me], "Tab") == "top",
           "Tab=" + Get(states[me], "Tab"));
        Ok("уход с вкладки убрал состояние профиля",
           !((IDictionary)Get(account, "_views")).Contains(me),
           "API_OnTabHidden отработал");

        // --- карточка уровня на Главной не тронута ---
        // Она и раньше ходила через API_GetPlayerSummary, а не API_GetPlayerProfile:
        // последний вызывался только внутри удалённого профиля.
        object summary = Call(menu, "GetAccountSummary", (Oxide.Core.Plugins.Plugin)account, player);
        Ok("Главная получает сводку аккаунта", summary != null,
           "API_GetPlayerSummary вернул " + ((IDictionary)summary).Count + " полей");

        var home = (IDictionary)summary;
        Ok("в сводке есть всё для карточки уровня",
           home.Contains("Level") && home.Contains("Exp") &&
           home.Contains("RequiredExp") && home.Contains("Progress"),
           "Level/Exp/RequiredExp/Progress");

        Ok("Главная рисуется без ошибок",
           Call(menu, "Open", player, "home") == null &&
           (string)Get(states[me], "Tab") == "home",
           CuiHelper.LastElementCount + " элементов");

        Console.WriteLine(fails == 0 ? "\nINTEGRATION: ALL PASS" : "\nINTEGRATION: " + fails + " FAILED");
        return fails;
    }
}
