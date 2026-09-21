using System;
using System.Collections.Generic;
using System.Globalization;
using Newtonsoft.Json;
using Oxide.Core;
using Oxide.Core.Plugins;
using UnityEngine;

namespace Oxide.Plugins
{
    [Info("ServerChat", "Flux", "1.0.0")]
    internal class ServerChat : RustPlugin
    {
        private const string AdminPermission = "serverchat.admin";

        private ConfigData _config;
        private Timer _newsTimer;
        private int _newsIndex;

        private readonly Dictionary<ulong, ulong> _lastPartner = new Dictionary<ulong, ulong>();
        private readonly Dictionary<ulong, float> _lastSent = new Dictionary<ulong, float>();
        private readonly List<BasePlayer> _matches = new List<BasePlayer>();
        private readonly System.Random _random = new System.Random();

        #region Configuration

        private class ConfigData
        {
            [JsonProperty("Префикс системных сообщений")]
            public string Prefix = "[СЕРВЕР]";

            [JsonProperty("Цвет префикса")]
            public string PrefixHex = "#65A30D";

            [JsonProperty("Аватар системных сообщений (SteamID64)")]
            public ulong AvatarSteamId = 0UL;

            [JsonProperty("Интервал новостей в секундах")]
            public float NewsIntervalSeconds = 600f;

            [JsonProperty("Новости в случайном порядке")]
            public bool NewsRandomOrder;

            [JsonProperty("Новости")]
            public List<NewsEntry> News = DefaultNews();

            [JsonProperty("Звук личного сообщения")]
            public string PmSound = "assets/bundled/prefabs/fx/invite_notice.prefab";

            [JsonProperty("Максимальная длина личного сообщения")]
            public int PmMaxLength = 256;

            [JsonProperty("Задержка между личными сообщениями в секундах")]
            public float PmCooldownSeconds = 2f;

            public static List<NewsEntry> DefaultNews()
            {
                return new List<NewsEntry>
                {
                    new NewsEntry("Приятной игры на сервере."),
                    new NewsEntry("Личное сообщение: /pm ник текст, ответ: /r текст.")
                };
            }
        }

        private class NewsEntry
        {
            [JsonProperty("Текст")]
            public string Text;

            [JsonProperty("Свой префикс")]
            public string Prefix;

            [JsonProperty("Свой цвет")]
            public string Hex;

            public NewsEntry() { }

            public NewsEntry(string text)
            {
                Text = text;
            }
        }

        protected override void LoadDefaultConfig()
        {
            _config = new ConfigData();
        }

        protected override void LoadConfig()
        {
            base.LoadConfig();
            try
            {
                _config = Config.ReadObject<ConfigData>();
                if (_config == null) throw new Exception("Config is null");
            }
            catch
            {
                PrintWarning("Конфиг ServerChat повреждён. Создан новый.");
                _config = new ConfigData();
            }

            if (_config.News == null) _config.News = ConfigData.DefaultNews();
            if (string.IsNullOrEmpty(_config.Prefix)) _config.Prefix = "[СЕРВЕР]";
            if (string.IsNullOrEmpty(_config.PrefixHex)) _config.PrefixHex = "#65A30D";
            if (string.IsNullOrEmpty(_config.PmSound)) _config.PmSound = "assets/bundled/prefabs/fx/invite_notice.prefab";

            _config.NewsIntervalSeconds = Mathf.Clamp(_config.NewsIntervalSeconds, 30f, 86400f);
            _config.PmCooldownSeconds = Mathf.Clamp(_config.PmCooldownSeconds, 0f, 60f);
            _config.PmMaxLength = Mathf.Clamp(_config.PmMaxLength, 16, 1024);

            SaveConfig();
        }

        protected override void SaveConfig()
        {
            Config.WriteObject(_config, true);
        }

        #endregion

        #region Lifecycle

        private void Init()
        {
            permission.RegisterPermission(AdminPermission, this);
        }

        private void OnServerInitialized()
        {
            RestartNewsTimer();
        }

        private void Unload()
        {
            if (_newsTimer != null && !_newsTimer.Destroyed)
                _newsTimer.Destroy();

            _newsTimer = null;
            _lastPartner.Clear();
            _lastSent.Clear();
            _matches.Clear();
        }

        private void OnPlayerDisconnected(BasePlayer player, string reason)
        {
            if (player == null) return;
            _lastPartner.Remove(player.userID);
            _lastSent.Remove(player.userID);
        }

        private void RestartNewsTimer()
        {
            if (_newsTimer != null && !_newsTimer.Destroyed)
                _newsTimer.Destroy();

            _newsIndex = 0;
            _newsTimer = timer.Every(_config.NewsIntervalSeconds, BroadcastNews);
        }

        private void BroadcastNews()
        {
            if (_config.News == null || _config.News.Count == 0) return;

            NewsEntry entry = _config.NewsRandomOrder
                ? _config.News[_random.Next(_config.News.Count)]
                : _config.News[_newsIndex % _config.News.Count];

            _newsIndex++;
            if (entry == null || string.IsNullOrEmpty(entry.Text)) return;

            Broadcast(entry.Text, entry.Prefix, null, entry.Hex);
        }

        #endregion

        #region Sending

        private void Send(BasePlayer player, string message, string prefix, string avatar, string hex)
        {
            if (player == null || !player.IsConnected || string.IsNullOrEmpty(message)) return;

            ulong avatarId = _config.AvatarSteamId;
            if (!string.IsNullOrEmpty(avatar))
                ulong.TryParse(avatar, out avatarId);

            player.SendConsoleCommand("chat.add", 2, avatarId, Compose(message, prefix, hex));
        }

        private void Broadcast(string message, string prefix, string avatar, string hex)
        {
            if (string.IsNullOrEmpty(message)) return;

            ulong avatarId = _config.AvatarSteamId;
            if (!string.IsNullOrEmpty(avatar))
                ulong.TryParse(avatar, out avatarId);

            string text = Compose(message, prefix, hex);

            foreach (BasePlayer player in BasePlayer.activePlayerList)
            {
                if (player == null || !player.IsConnected) continue;
                player.SendConsoleCommand("chat.add", 2, avatarId, text);
            }
        }

        private string Compose(string message, string prefix, string hex)
        {
            string title = prefix ?? _config.Prefix;
            string color = string.IsNullOrEmpty(hex) ? _config.PrefixHex : hex;

            if (string.IsNullOrEmpty(title))
                return message;

            return "<color=" + color + ">" + title + "</color> " + message;
        }

        private void PlaySound(BasePlayer player)
        {
            if (player == null || !player.IsConnected || string.IsNullOrEmpty(_config.PmSound)) return;

            EffectNetwork.Send(
                new Effect(_config.PmSound, player, 0u, Vector3.zero, Vector3.zero),
                player.Connection);
        }

        #endregion

        #region Public API

        [HookMethod("API_ALERT_PLAYER")]
        public void API_ALERT_PLAYER(BasePlayer player, string message, string customPrefix = null,
            string customAvatar = null, string customHex = null)
        {
            Send(player, message, customPrefix, customAvatar, customHex);
        }

        [HookMethod("API_ALERT")]
        public void API_ALERT(string message, string customPrefix = null,
            string customAvatar = null, string customHex = null)
        {
            Broadcast(message, customPrefix, customAvatar, customHex);
        }

        #endregion

        #region Admin commands

        [ChatCommand("alert")]
        private void CmdAlert(BasePlayer player, string command, string[] args)
        {
            if (!IsAdmin(player)) return;

            string message = Join(args, 0);
            if (string.IsNullOrEmpty(message))
            {
                Reply(player, "UsageAlert");
                return;
            }

            Broadcast(message, null, null, null);
        }

        [ChatCommand("alert.player")]
        private void CmdAlertPlayer(BasePlayer player, string command, string[] args)
        {
            if (!IsAdmin(player)) return;

            if (args == null || args.Length < 2)
            {
                Reply(player, "UsageAlertPlayer");
                return;
            }

            BasePlayer target = FindPlayer(player, args[0]);
            if (target == null) return;

            string message = Join(args, 1);
            if (string.IsNullOrEmpty(message))
            {
                Reply(player, "UsageAlertPlayer");
                return;
            }

            Send(target, message, null, null, null);
            Reply(player, "AlertSent", target.displayName);
        }

        [ChatCommand("news.add")]
        private void CmdNewsAdd(BasePlayer player, string command, string[] args)
        {
            if (!IsAdmin(player)) return;

            string text = Join(args, 0);
            if (string.IsNullOrEmpty(text))
            {
                Reply(player, "UsageNewsAdd");
                return;
            }

            _config.News.Add(new NewsEntry(text));
            SaveConfig();
            Reply(player, "NewsAdded", _config.News.Count.ToString(CultureInfo.InvariantCulture));
        }

        [ChatCommand("news.remove")]
        private void CmdNewsRemove(BasePlayer player, string command, string[] args)
        {
            if (!IsAdmin(player)) return;

            int number;
            if (args == null || args.Length < 1 || !int.TryParse(args[0], out number))
            {
                Reply(player, "UsageNewsRemove");
                return;
            }

            if (number < 1 || number > _config.News.Count)
            {
                Reply(player, "NewsNotFound", number.ToString(CultureInfo.InvariantCulture));
                return;
            }

            _config.News.RemoveAt(number - 1);
            SaveConfig();
            Reply(player, "NewsRemoved", number.ToString(CultureInfo.InvariantCulture));
        }

        [ChatCommand("news.list")]
        private void CmdNewsList(BasePlayer player, string command, string[] args)
        {
            if (!IsAdmin(player)) return;

            if (_config.News.Count == 0)
            {
                Reply(player, "NewsEmpty");
                return;
            }

            Reply(player, "NewsHeader", _config.News.Count.ToString(CultureInfo.InvariantCulture),
                _config.NewsIntervalSeconds.ToString("0", CultureInfo.InvariantCulture));

            for (int i = 0; i < _config.News.Count; i++)
            {
                NewsEntry entry = _config.News[i];
                if (entry == null) continue;

                Send(player, (i + 1).ToString(CultureInfo.InvariantCulture) + ". " + entry.Text,
                    string.Empty, null, null);
            }
        }

        [ChatCommand("news.interval")]
        private void CmdNewsInterval(BasePlayer player, string command, string[] args)
        {
            if (!IsAdmin(player)) return;

            float seconds;
            if (args == null || args.Length < 1 ||
                !float.TryParse(args[0], NumberStyles.Float, CultureInfo.InvariantCulture, out seconds))
            {
                Reply(player, "UsageNewsInterval");
                return;
            }

            _config.NewsIntervalSeconds = Mathf.Clamp(seconds, 30f, 86400f);
            SaveConfig();
            RestartNewsTimer();
            Reply(player, "NewsInterval",
                _config.NewsIntervalSeconds.ToString("0", CultureInfo.InvariantCulture));
        }

        #endregion

        #region Private messages

        [ChatCommand("pm")]
        private void CmdPm(BasePlayer player, string command, string[] args)
        {
            if (player == null) return;

            if (args == null || args.Length < 2)
            {
                Reply(player, "UsagePm");
                return;
            }

            if (OnCooldown(player)) return;

            BasePlayer target = FindPlayer(player, args[0]);
            if (target == null) return;

            Deliver(player, target, Join(args, 1));
        }

        [ChatCommand("r")]
        private void CmdReply(BasePlayer player, string command, string[] args)
        {
            if (player == null) return;

            string message = Join(args, 0);
            if (string.IsNullOrEmpty(message))
            {
                Reply(player, "UsageReply");
                return;
            }

            ulong partnerId;
            if (!_lastPartner.TryGetValue(player.userID, out partnerId))
            {
                Reply(player, "NoPartner");
                return;
            }

            BasePlayer target = BasePlayer.FindByID(partnerId);
            if (target == null || !target.IsConnected)
            {
                Reply(player, "PartnerOffline");
                return;
            }

            if (OnCooldown(player)) return;

            Deliver(player, target, message);
        }

        private void Deliver(BasePlayer sender, BasePlayer target, string message)
        {
            if (string.IsNullOrEmpty(message))
            {
                Reply(sender, "UsagePm");
                return;
            }

            if (target == sender)
            {
                Reply(sender, "PmSelf");
                return;
            }

            if (message.Length > _config.PmMaxLength)
                message = message.Substring(0, _config.PmMaxLength);

            Send(target, message, Format(target, "PmFrom", sender.displayName), null, null);
            Send(sender, message, Format(sender, "PmTo", target.displayName), null, null);
            PlaySound(target);

            _lastPartner[sender.userID] = target.userID;
            _lastPartner[target.userID] = sender.userID;
            _lastSent[sender.userID] = Time.realtimeSinceStartup;
        }

        private bool OnCooldown(BasePlayer player)
        {
            float last;
            if (!_lastSent.TryGetValue(player.userID, out last)) return false;

            float left = _config.PmCooldownSeconds - (Time.realtimeSinceStartup - last);
            if (left <= 0f) return false;

            Reply(player, "Cooldown", Mathf.CeilToInt(left).ToString(CultureInfo.InvariantCulture));
            return true;
        }

        #endregion

        #region Helpers

        private BasePlayer FindPlayer(BasePlayer sender, string needle)
        {
            if (string.IsNullOrEmpty(needle)) return null;

            ulong userId;
            if (needle.Length == 17 && ulong.TryParse(needle, out userId))
            {
                BasePlayer byId = BasePlayer.FindByID(userId);
                if (byId != null && byId.IsConnected) return byId;

                Reply(sender, "PlayerOffline", needle);
                return null;
            }

            _matches.Clear();

            foreach (BasePlayer online in BasePlayer.activePlayerList)
            {
                if (online == null || !online.IsConnected || online.IsNpc) continue;
                if (string.IsNullOrEmpty(online.displayName)) continue;

                if (online.displayName.Equals(needle, StringComparison.OrdinalIgnoreCase))
                {
                    _matches.Clear();
                    _matches.Add(online);
                    break;
                }

                if (online.displayName.IndexOf(needle, StringComparison.OrdinalIgnoreCase) >= 0)
                    _matches.Add(online);
            }

            if (_matches.Count == 0)
            {
                Reply(sender, "PlayerOffline", needle);
                return null;
            }

            if (_matches.Count == 1)
                return _matches[0];

            Reply(sender, "ManyMatches", _matches.Count.ToString(CultureInfo.InvariantCulture));

            for (int i = 0; i < _matches.Count && i < 10; i++)
                Send(sender, _matches[i].displayName, string.Empty, null, null);

            return null;
        }

        private bool IsAdmin(BasePlayer player)
        {
            if (player == null) return false;
            if (permission.UserHasPermission(player.UserIDString, AdminPermission)) return true;

            Reply(player, "NoPermission");
            return false;
        }

        private static string Join(string[] args, int from)
        {
            if (args == null || args.Length <= from) return string.Empty;
            return string.Join(" ", args, from, args.Length - from).Trim();
        }

        private string Format(BasePlayer player, string key, string argument)
        {
            return string.Format(lang.GetMessage(key, this, player == null ? null : player.UserIDString),
                argument);
        }

        private void Reply(BasePlayer player, string key, string first = null, string second = null)
        {
            if (player == null) return;

            string text = lang.GetMessage(key, this, player.UserIDString);
            if (first != null) text = string.Format(text, first, second ?? string.Empty);

            Send(player, text, null, null, null);
        }

        #endregion

        #region Lang

        protected override void LoadDefaultMessages()
        {
            lang.RegisterMessages(new Dictionary<string, string>
            {
                ["PmFrom"] = "[ЛС от {0}]:",
                ["PmTo"] = "[ЛС для {0}]:",
                ["UsagePm"] = "Использование: /pm ник текст",
                ["UsageReply"] = "Использование: /r текст",
                ["NoPartner"] = "Вам ещё никто не писал.",
                ["PartnerOffline"] = "Собеседник вышел с сервера.",
                ["PlayerOffline"] = "Игрок «{0}» не найден среди тех, кто сейчас на сервере.",
                ["ManyMatches"] = "Найдено игроков: {0}. Уточните ник.",
                ["PmSelf"] = "Нельзя написать самому себе.",
                ["Cooldown"] = "Подождите {0} сек. перед следующим сообщением.",
                ["NoPermission"] = "У вас нет доступа к этой команде.",
                ["UsageAlert"] = "Использование: /alert текст",
                ["UsageAlertPlayer"] = "Использование: /alert.player ник текст",
                ["AlertSent"] = "Сообщение отправлено игроку {0}.",
                ["UsageNewsAdd"] = "Использование: /news.add текст",
                ["UsageNewsRemove"] = "Использование: /news.remove номер",
                ["UsageNewsInterval"] = "Использование: /news.interval секунды",
                ["NewsAdded"] = "Новость добавлена под номером {0}.",
                ["NewsRemoved"] = "Новость {0} удалена.",
                ["NewsNotFound"] = "Новости с номером {0} нет.",
                ["NewsEmpty"] = "Список новостей пуст.",
                ["NewsHeader"] = "Новостей: {0}, интервал {1} сек.",
                ["NewsInterval"] = "Интервал новостей: {0} сек."
            }, this, "ru");

            lang.RegisterMessages(new Dictionary<string, string>
            {
                ["PmFrom"] = "[PM from {0}]:",
                ["PmTo"] = "[PM to {0}]:",
                ["UsagePm"] = "Usage: /pm name text",
                ["UsageReply"] = "Usage: /r text",
                ["NoPartner"] = "Nobody has messaged you yet.",
                ["PartnerOffline"] = "That player has left the server.",
                ["PlayerOffline"] = "No player named \"{0}\" is online.",
                ["ManyMatches"] = "Found {0} players. Be more specific.",
                ["PmSelf"] = "You cannot message yourself.",
                ["Cooldown"] = "Wait {0}s before sending another message.",
                ["NoPermission"] = "You do not have access to this command.",
                ["UsageAlert"] = "Usage: /alert text",
                ["UsageAlertPlayer"] = "Usage: /alert.player name text",
                ["AlertSent"] = "Message sent to {0}.",
                ["UsageNewsAdd"] = "Usage: /news.add text",
                ["UsageNewsRemove"] = "Usage: /news.remove number",
                ["UsageNewsInterval"] = "Usage: /news.interval seconds",
                ["NewsAdded"] = "News item added as number {0}.",
                ["NewsRemoved"] = "News item {0} removed.",
                ["NewsNotFound"] = "There is no news item number {0}.",
                ["NewsEmpty"] = "The news list is empty.",
                ["NewsHeader"] = "News items: {0}, interval {1}s.",
                ["NewsInterval"] = "News interval: {0}s."
            }, this, "en");
        }

        #endregion
    }
}
