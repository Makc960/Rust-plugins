using System.Collections.Generic;

namespace Oxide.Plugins
{
    [Info("InstantCraft", "Flux", "0.0.1")]
    public class InstantCraft : RustPlugin
    {
        private object OnItemCraft(ItemCraftTask task, BasePlayer owner, Item fromTempBlueprint)
        {
            if (task == null || task.cancelled || task.blueprint == null || task.amount <= 0 || fromTempBlueprint != null)
                return null;

            if (owner == null || owner.IsDestroyed || owner.inventory == null || owner.inventory.crafting == null)
                return null;

            ItemCrafter crafter = owner.inventory.crafting;

            task.workbenchEntity = owner.GetCachedCraftLevelWorkbench();
            owner.Command("note.craft_add", task.taskUID, task.blueprint.targetItem.itemid, task.amount, task.skinID);

            while (task.amount > 0 && !task.cancelled)
                crafter.FinishCrafting(task);

            ReturnLeftovers(task, owner);

            return true;
        }

        private static void ReturnLeftovers(ItemCraftTask task, BasePlayer owner)
        {
            List<Item> taken = task.takenItems;

            if (taken == null || taken.Count == 0)
                return;

            ItemContainer main = owner.inventory.containerMain;

            for (int i = 0; i < taken.Count; i++)
            {
                Item item = taken[i];

                if (item == null || item.amount <= 0)
                    continue;

                if (!item.MoveToContainer(main))
                {
                    item.Drop(main.dropPosition, main.dropVelocity);
                    owner.Command("note.inv", item.info.itemid, -item.amount);
                }
            }

            taken.Clear();
        }

        private void Unload()
        {
        }
    }
}
