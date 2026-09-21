using System;
using System.Collections.Generic;
using Oxide.Core.Configuration;
using Oxide.Core.Libraries;

namespace Oxide.Plugins
{
    // Oxide.CSharp.cs:2164
    public class InfoAttribute : Attribute
    {
        public InfoAttribute(string Title, string Author, string Version) { }
        public InfoAttribute(string Title, string Author, double Version) { }
        public int ResourceId { get; set; }
    }

    // Oxide.CSharp.cs:2205
    public class DescriptionAttribute : Attribute
    {
        public DescriptionAttribute(string description) { }
    }

    public class ChatCommandAttribute : Attribute
    {
        public ChatCommandAttribute(string command) { }
    }

    public class ConsoleCommandAttribute : Attribute
    {
        public ConsoleCommandAttribute(string command) { }
    }

    public class CommandAttribute : Attribute
    {
        public CommandAttribute(params string[] commands) { }
    }

    public class PermissionAttribute : Attribute
    {
        public PermissionAttribute(string permission) { }
    }

    // Oxide.CSharp.cs:2884 - обёртка над Core-таймером, именно её видят плагины.
    public class Timer
    {
        public bool Destroyed { get; private set; }
        public void Reset(float delay = -1f, int repetitions = 1) { }
        public void Destroy() { Destroyed = true; }
        public void DestroyToPool() { Destroyed = true; }
    }

    // Oxide.CSharp.cs:2918
    public class PluginTimers
    {
        public Timer Once(float seconds, Action callback) { return new Timer(); }
        public Timer In(float seconds, Action callback) { return new Timer(); }
        public Timer Every(float interval, Action callback) { return new Timer(); }
        public Timer Repeat(float interval, int repeats, Action callback) { return new Timer(); }
        public void Destroy(ref Timer timer) { }
    }

    public abstract class CSharpPlugin : Oxide.Core.Plugins.Plugin
    {
        protected DynamicConfigFile Config = new DynamicConfigFile();
        protected Permission permission = new Permission();
        protected PluginTimers timer = new PluginTimers();
        protected Lang lang = new Lang();

        // Name/Title/IsLoaded наследуются от Plugin: отдельные new-свойства
        // раздваивали бы состояние и ломали межплагинные проверки.
        public string Author { get; set; }

        protected virtual void LoadDefaultConfig() { }
        protected virtual void LoadConfig() { }
        protected virtual void SaveConfig() { }
        protected virtual void LoadDefaultMessages() { }

        protected void PrintWarning(string format, params object[] args) { }
        protected void PrintError(string format, params object[] args) { }
        protected void Puts(string format, params object[] args) { }
        protected void LogWarning(string format, params object[] args) { }
        protected void LogError(string format, params object[] args) { }

        protected void NextTick(Action callback) { }
        protected void NextFrame(Action callback) { }
    }

    // Oxide.Rust.cs:65
    public abstract class RustPlugin : CSharpPlugin
    {
        protected void PrintToChat(string format, params object[] args) { }
        protected void PrintToChat(BasePlayer player, string format, params object[] args) { }
        protected void PrintToConsole(string format, params object[] args) { }
        protected void PrintToConsole(BasePlayer player, string format, params object[] args) { }
        protected void SendReply(ConsoleSystem.Arg arg, string format, params object[] args) { }
        protected void SendReply(BasePlayer player, string format, params object[] args) { }
        protected void SendWarning(ConsoleSystem.Arg arg, string format, params object[] args) { }
    }

    // Oxide.Rust.cs:58 RustExtensionMethods
    public static class RustExtensionMethods
    {
        public static bool IsSteamId(this ulong id) { return id > 76561197960265728UL; }
        public static bool IsSteamId(this EncryptedValue<ulong> id) { return ((ulong)id).IsSteamId(); }
    }
}
