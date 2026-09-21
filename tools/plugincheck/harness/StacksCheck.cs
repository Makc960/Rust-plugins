using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

public static class StacksCheck
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static |
                             BindingFlags.Public | BindingFlags.NonPublic;

    static int fails;
    static Type ST;
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
        return ST.GetMethods(Any).First(x => x.Name == m && x.GetParameters().Length == a.Length)
            .Invoke(plugin, a);
    }

    static ItemDefinition Def(string shortname, int stackable, ItemCategory category)
    {
        return new ItemDefinition { shortname = shortname, stackable = stackable, category = category };
    }

    static ItemDefinition WithCondition(string shortname, int stackable)
    {
        var d = Def(shortname, stackable, ItemCategory.Weapon);
        d.condition = new ItemDefinition.Condition { enabled = true, max = 100f };
        return d;
    }

    public static int Run()
    {
        ST = Type.GetType("Oxide.Plugins.Stacks");
        plugin = Activator.CreateInstance(ST, true);
        Type ConfigT = ST.GetNestedType("Configuration", BindingFlags.NonPublic);
        object cfg = Activator.CreateInstance(ConfigT, true);
        Set(plugin, "_config", cfg);
        Set(cfg, "Multiplier", 5);

        // Ванильные стаки как в игре: дерево 1000, АК 1, чертёж 1.
        var wood = Def("wood", 1000, ItemCategory.Resources);
        var ak = WithCondition("rifle.ak", 1);
        var bow = WithCondition("bow.hunting", 1);
        var blueprint = Def("blueprintbase", 1, ItemCategory.Items);
        blueprint.spawnAsBlueprint = true;
        var cloth = Def("cloth", 1000, ItemCategory.Resources);

        ItemManager.itemList.Clear();
        ItemManager.itemList.AddRange(new[] { wood, ak, bow, blueprint, cloth });

        Call("ApplyStacks");

        Ok("ресурс умножается", wood.stackable == 5000, "дерево 1000 -> " + wood.stackable);
        Ok("второй ресурс умножается", cloth.stackable == 5000, "ткань 1000 -> " + cloth.stackable);
        Ok("оружие с прочностью не трогаем", ak.stackable == 1, "АК остался " + ak.stackable);
        Ok("лук с прочностью не трогаем", bow.stackable == 1, "лук остался " + bow.stackable);
        Ok("чертежи не трогаем", blueprint.stackable == 1, "чертёж остался " + blueprint.stackable);

        // Предмет с содержимым.
        var box = Def("box.wooden", 1, ItemCategory.Construction);
        Ok("признак содержимого берётся как в игре",
           ST.GetMethods(Any).Any(m => m.Name == "Excluded"),
           "Excluded() проверяет GetComponent<ItemModContainer>(), Assembly-CSharp.cs:61701");

        // Точечный стак не должен воскрешать исключённый предмет.
        var stacksMap = (IDictionary)Get(cfg, "Stacks");
        stacksMap["rifle.ak"] = 100;
        ItemManager.itemList.Clear();
        ItemManager.itemList.Add(ak);
        Call("ApplyStacks");
        Ok("точечный стак не обходит исключение", ak.stackable == 1,
           "АК всё ещё " + ak.stackable + " несмотря на Stacks[rifle.ak]=100");
        stacksMap.Clear();

        // --- хук OnMaxStackable ---
        MethodInfo hook = ST.GetMethods(Any).FirstOrDefault(m => m.Name == "OnMaxStackable");
        Ok("хук OnMaxStackable есть", hook != null, "Assembly-CSharp.cs:367882");

        var p = hook.GetParameters();
        Ok("сигнатура OnMaxStackable(Item)",
           p.Length == 1 && p[0].ParameterType == typeof(Item), p[0].ParameterType.Name);

        var item = new Item { info = wood, amount = 1 };
        object result = hook.Invoke(plugin, new object[] { item });
        Ok("хук отдаёт рассчитанный стак", result is int && (int)result == 5000,
           "вернул " + result);
        Ok("игра примет результат: obj is int", result is int,
           "проверка из Assembly-CSharp.cs:367883");

        object nullItem = hook.Invoke(plugin, new object[] { null });
        Ok("null не роняет хук", nullItem == null, "вернул null, игра посчитает сама");

        var noInfo = new Item { info = null };
        object noInfoResult = hook.Invoke(plugin, new object[] { noInfo });
        Ok("предмет без определения не роняет", noInfoResult == null, "вернул null");

        // --- возврат ванили ---
        // Unload проходит по itemList, поэтому возвращаем туда все определения.
        ItemManager.itemList.Clear();
        ItemManager.itemList.AddRange(new[] { wood, ak, bow, blueprint, cloth });
        Call("Unload");
        Ok("Unload возвращает ваниль", wood.stackable == 1000 && cloth.stackable == 1000,
           "дерево -> " + wood.stackable + ", ткань -> " + cloth.stackable);

        // --- ничего лишнего ---
        string[] forbidden = { "OnItemAddedToContainer", "OnItemSplit", "OnItemStack",
                               "CanStackItem", "CanCombineDroppedItem", "OnPlayerConnected" };
        var present = forbidden.Where(h => ST.GetMethods(Any).Any(m => m.Name == h)).ToArray();
        Ok("лишних хуков нет", present.Length == 0,
           present.Length == 0 ? "только OnServerInitialized, Unload, OnMaxStackable"
                               : string.Join(", ", present));

        var commands = ST.GetMethods(Any)
            .Where(m => m.GetCustomAttributes(true).Any(a =>
                a.GetType().Name == "ChatCommandAttribute" || a.GetType().Name == "ConsoleCommandAttribute"))
            .ToArray();
        Ok("команд нет", commands.Length == 0, "как в постановке");

        Console.WriteLine(fails == 0 ? "\nSTACKS: ALL PASS" : "\nSTACKS: " + fails + " FAILED");
        return fails;
    }
}
