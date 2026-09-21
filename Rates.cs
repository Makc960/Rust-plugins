namespace Oxide.Plugins
{
    [Info("Rates", "Flux", "1.0.0")]
    [Description("Doubles every gathered resource")]
    internal class Rates : RustPlugin
    {
        private const int Multiplier = 2;

        private void OnDispenserGather(ResourceDispenser dispenser, BasePlayer player, Item item)
        {
            if (item == null)
            {
                return;
            }

            item.amount *= Multiplier;
        }

        private void OnDispenserBonus(ResourceDispenser dispenser, BasePlayer player, Item item)
        {
            if (item == null)
            {
                return;
            }

            item.amount *= Multiplier;
        }

        private void OnCollectiblePickedup(CollectibleEntity collectible, BasePlayer player, Item item)
        {
            if (item == null)
            {
                return;
            }

            item.amount *= Multiplier;
        }

        private void OnGrowableGathered(GrowableEntity plant, Item item, BasePlayer player)
        {
            if (item == null)
            {
                return;
            }

            item.amount *= Multiplier;
        }

        private void OnQuarryGather(MiningQuarry quarry, Item item)
        {
            if (item == null)
            {
                return;
            }

            item.amount *= Multiplier;
        }

        private void OnExcavatorGather(ExcavatorArm arm, Item item)
        {
            if (item == null)
            {
                return;
            }

            item.amount *= Multiplier;
        }

        private void Unload()
        {
        }
    }
}
