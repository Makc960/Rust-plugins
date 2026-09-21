using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

// InstantCraft: задача завершается целиком в момент постановки (OnItemCraft), ингредиенты списываются
// ровно один раз, лишнего не выдаётся и не возвращается; чертёж-образец и отменённые задачи не трогаются.
public static class InstantCraftCheck
{
    const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;
    static int fails;
    static void Ok(string name, bool cond, string detail)
    {
        Console.WriteLine((cond ? "  PASS  " : "  FAIL  ") + name + "   " + detail);
        if (!cond) fails++;
    }

    static ItemDefinition Def(int id, string name) { return new ItemDefinition { itemid = id, shortname = name }; }

    // Ванильная постановка задачи до хука (CraftItem :365649-365683): ингредиенты уже сняты в takenItems.
    static ItemCraftTask Task(BasePlayer p, int amount, int skin, out List<Item> taken)
    {
        var metal = Def(69511070, "metal.fragments");
        var bp = new ItemBlueprint { targetItem = Def(-1211166256, "ammo.rifle"), amountToCreate = 1, ingredients = new[] { new ItemAmount { itemDef = metal, amount = 1f } } };
        taken = new List<Item> { new Item { info = metal, amount = amount } };
        p.inventory.crafting.owner = p;
        p.inventory.crafting.containers.Add(p.inventory.containerMain);
        p.inventory.crafting.taskUID++;
        return new ItemCraftTask { blueprint = bp, takenItems = taken, amount = amount, skinID = skin, taskUID = p.inventory.crafting.taskUID };
    }

    public static int Run()
    {
        Type T = Type.GetType("Oxide.Plugins.InstantCraft");
        object plugin = Activator.CreateInstance(T, true);
        var hook = T.GetMethod("OnItemCraft", Any);
        Func<ItemCraftTask, BasePlayer, Item, object> call = (t, p, bp) => hook.Invoke(plugin, new object[] { t, p, bp });

        // 100 патронов: 100 FinishCrafting подряд, задача не в очереди, хук вернул true
        var player = new BasePlayer { userID = 1UL, UserIDString = "1", displayName = "T" };
        List<Item> taken;
        var task = Task(player, 100, 0, out taken);
        object r = call(task, player, null);
        var main = player.inventory.containerMain.itemList;
        Ok("100 крафтов -> 100 FinishCrafting за один вызов, задача закрыта", r is bool && (bool)r && player.inventory.crafting.Finished == 100 && task.amount == 0 && task.numCrafted == 100,
           "Finished=" + player.inventory.crafting.Finished + " amount=" + task.amount + " ret=" + r);
        Ok("выдано ровно 100 предметов, очередь пуста", main.Count == 100 && player.inventory.crafting.queue.Count == 0, "items=" + main.Count + " queue=" + player.inventory.crafting.queue.Count);
        Ok("ингредиенты списаны один раз, возвратов нет", taken.Count == 0 && task.takenItems.Count == 0 && main.All(i => i.info.shortname == "ammo.rifle"), "takenItems=" + task.takenItems.Count);
        var cmds = player.Commands;
        Ok("клиенту: note.craft_add, затем craft_done x100 с убывающим остатком до 0",
           (string)cmds[0][0] == "note.craft_add" && cmds.Count(c => (string)c[0] == "note.craft_done") == 100 && (int)cmds.Last(c => (string)c[0] == "note.craft_done")[3] == 0,
           "первая=" + cmds[0][0] + " done=" + cmds.Count(c => (string)c[0] == "note.craft_done"));

        // 10 АК со скином: skin доходит до FinishCrafting
        player = new BasePlayer { userID = 2UL, UserIDString = "2", displayName = "T" };
        task = Task(player, 10, 12345, out taken);
        call(task, player, null);
        Ok("10 предметов со скином из задачи", player.inventory.containerMain.itemList.Count == 10 && player.inventory.containerMain.itemList.All(i => i.skin == 12345UL), "skins ok");

        // Полный инвентарь: предметы дропаются по одному, задача всё равно закрыта, дублей нет
        player = new BasePlayer { userID = 3UL, UserIDString = "3", displayName = "T" };
        player.inventory.GiveFails = true;
        task = Task(player, 5, 0, out taken);
        call(task, player, null);
        Ok("полный инвентарь: 5 FinishCrafting, в инвентаре 0, note.inv +/- парами (дроп), задача закрыта",
           player.inventory.crafting.Finished == 5 && player.inventory.containerMain.itemList.Count == 0 && task.amount == 0 && player.Commands.Count(c => (string)c[0] == "note.inv") == 10,
           "Finished=" + player.inventory.crafting.Finished + " inv=" + player.inventory.containerMain.itemList.Count);

        // Чертёж-образец (fromTempBlueprint) - ваниль
        player = new BasePlayer { userID = 4UL, UserIDString = "4", displayName = "T" };
        task = Task(player, 3, 0, out taken);
        r = call(task, player, new Item { info = Def(1, "bp") });
        Ok("крафт из чертежа-образца не трогаем: null, FinishCrafting не вызван", r == null && player.inventory.crafting.Finished == 0 && task.amount == 3, "ret=" + (r ?? "null"));

        // Отменённая задача - ваниль
        player = new BasePlayer { userID = 5UL, UserIDString = "5", displayName = "T" };
        task = Task(player, 3, 0, out taken); task.cancelled = true;
        r = call(task, player, null);
        Ok("task.cancelled -> null, ничего не делаем", r == null && player.inventory.crafting.Finished == 0, "ret=" + (r ?? "null"));

        // Остаток в takenItems (не должно быть, но если есть) - возвращается игроку, не уничтожается
        player = new BasePlayer { userID = 6UL, UserIDString = "6", displayName = "T" };
        task = Task(player, 2, 0, out taken);
        taken.Add(new Item { info = Def(7, "extra"), amount = 3 });
        call(task, player, null);
        Ok("лишний ингредиент возвращён в инвентарь, takenItems очищен",
           player.inventory.containerMain.itemList.Any(i => i.info.shortname == "extra" && i.amount == 3) && task.takenItems.Count == 0, "extra в containerMain");

        // owner null / destroyed
        task = Task(player, 2, 0, out taken);
        bool threw = false; object r1 = null, r2 = null;
        try { r1 = call(task, null, null); r2 = call(task, new BasePlayer { IsDestroyed = true, userID = 7UL }, null); } catch (Exception) { threw = true; }
        Ok("owner null/destroyed -> null без исключения", !threw && r1 == null && r2 == null && task.amount == 2, threw ? "исключение" : "ок");

        Console.WriteLine(fails == 0 ? "\nINSTANTCRAFT: ALL PASS" : "\nINSTANTCRAFT: " + fails + " FAILED");
        return fails;
    }
}
