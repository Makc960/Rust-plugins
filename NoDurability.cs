namespace Oxide.Plugins
{
    [Info("NoDurability", "Flux", "1.0.0")]
    [Description("Items never lose condition, except the rocket launcher")]
    internal class NoDurability : RustPlugin
    {
        private const string RocketLauncherShortname = "rocket.launcher";

        private int _rocketLauncherId = -1;

        private bool _resolved;

        private void OnServerInitialized()
        {
            Resolve();
        }

        private void Resolve()
        {
            _resolved = true;

            ItemDefinition definition = ItemManager.FindItemDefinition(RocketLauncherShortname);
            if (definition == null)
            {
                PrintWarning("Item definition '" + RocketLauncherShortname + "' not found, rocket launcher will not wear down");
                return;
            }

            _rocketLauncherId = definition.itemid;
        }

        private void OnLoseCondition(Item item, ref float amount)
        {
            if (!_resolved)
            {
                Resolve();
            }

            if (item.info.itemid == _rocketLauncherId)
            {
                return;
            }

            amount = 0f;
        }

        private void Unload()
        {
        }
    }
}
