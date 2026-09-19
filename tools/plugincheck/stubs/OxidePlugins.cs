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

    public class HookMethodAttribute : Attribute
    {
        public HookMethodAttribute(string name) { }
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

    public abstract class CSharpPlugin : Oxide.Core.Plugins.Plugin
    {
        protected DynamicConfigFile Config = new DynamicConfigFile();
        protected Permission permission = new Permission();
        protected Timer timer = new Timer();
        protected Lang lang = new Lang();

        public new string Name { get; set; }
        public new string Title { get; set; }
        public new bool IsLoaded { get; set; }
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
