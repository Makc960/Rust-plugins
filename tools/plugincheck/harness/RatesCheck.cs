using System;
using System.Linq;
using System.Reflection;

public static class RatesCheck
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;

    static int fails;
    static Type RT;
    static object plugin;

    static void Ok(string name, bool cond, string detail)
    {
        Console.WriteLine((cond ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (!cond) fails++;
    }

    // Сигнатуры так, как игра их вызывает: имя хука -> типы аргументов CallHook.
    // Номера строк — Assembly-CSharp.cs.
    static readonly object[][] Expected =
    {
        new object[] { "OnDispenserGather",    new[] { typeof(ResourceDispenser), typeof(BasePlayer), typeof(Item) }, 296540 },
        new object[] { "OnDispenserBonus",     new[] { typeof(ResourceDispenser), typeof(BasePlayer), typeof(Item) }, 296409 },
        new object[] { "OnCollectiblePickedup",new[] { typeof(CollectibleEntity), typeof(BasePlayer), typeof(Item) }, 116024 },
        new object[] { "OnGrowableGathered",   new[] { typeof(GrowableEntity), typeof(Item), typeof(BasePlayer) },    135672 },
        new object[] { "OnQuarryGather",       new[] { typeof(MiningQuarry), typeof(Item) },                          301459 },
        new object[] { "OnExcavatorGather",    new[] { typeof(ExcavatorArm), typeof(Item) },                          130849 },
    };

    static Item NewItem(int amount)
    {
        return new Item { amount = amount, info = new ItemDefinition { itemid = 1, shortname = "wood" } };
    }

    static object[] ArgsFor(Type[] types, Item item)
    {
        return types.Select(t => t == typeof(Item) ? (object)item : null).ToArray();
    }

    public static int Run()
    {
        RT = Type.GetType("Oxide.Plugins.Rates");
        plugin = Activator.CreateInstance(RT, true);
        Ok("плагин найден", RT != null, "Oxide.Plugins.Rates");

        foreach (var row in Expected)
        {
            string hook = (string)row[0];
            Type[] types = (Type[])row[1];
            int line = (int)row[2];

            MethodInfo method = RT.GetMethods(Any).FirstOrDefault(x => x.Name == hook);
            if (method == null) { Ok(hook + ": метод есть", false, "не найден"); continue; }

            Type[] actual = method.GetParameters().Select(p => p.ParameterType).ToArray();
            bool match = actual.Length == types.Length &&
                         !actual.Where((t, i) => t != types[i]).Any();
            Ok(hook + ": сигнатура как в игре", match,
               "Assembly-CSharp.cs:" + line + " — " + string.Join(", ", actual.Select(t => t.Name)));

            // умножение
            Item item = NewItem(37);
            method.Invoke(plugin, ArgsFor(types, item));
            Ok(hook + ": ×2", item.amount == 74, "37 -> " + item.amount);

            // ранний выход на null
            try
            {
                method.Invoke(plugin, ArgsFor(types, null));
                Ok(hook + ": null не роняет", true, "вернулся без исключения");
            }
            catch (Exception ex)
            {
                Ok(hook + ": null не роняет", false, (ex.InnerException ?? ex).GetType().Name);
            }

            // возвращает void: любой не-null результат отменил бы добычу
            Ok(hook + ": возвращает void", method.ReturnType == typeof(void), method.ReturnType.Name);
        }

        // Хуки, которых быть не должно: лут, крафт, переработчик, печи.
        string[] forbidden = { "OnItemCraftFinished", "OnLootEntity", "OnRecyclerToggle",
                               "OnOvenCook", "OnOvenStarted", "OnDispenserGathered",
                               "OnDispenserBonusReceived", "OnCollectiblePickup" };
        var present = forbidden.Where(h => RT.GetMethods(Any).Any(m => m.Name == h)).ToArray();
        Ok("лут, крафт, печи и переработчик не тронуты", present.Length == 0,
           present.Length == 0 ? "лишних хуков нет" : "лишние: " + string.Join(", ", present));

        Ok("Unload пустой",
           RT.GetMethods(Any).Any(m => m.Name == "Unload" && m.GetParameters().Length == 0),
           "метод на месте");

        var extras = RT.GetMethods(Any | BindingFlags.DeclaredOnly)
            .Where(m => m.Name.StartsWith("On") || m.Name.StartsWith("API_") || m.Name.StartsWith("Cmd"))
            .Select(m => m.Name).Distinct().ToArray();
        Ok("никаких лишних точек входа", extras.Length == 6,
           extras.Length + " хуков: " + string.Join(", ", extras.OrderBy(x => x)));

        Console.WriteLine(fails == 0 ? "\nRATES: ALL PASS" : "\nRATES: " + fails + " FAILED");
        return fails;
    }
}
