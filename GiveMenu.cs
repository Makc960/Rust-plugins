using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Oxide.Core;
using Oxide.Core.Libraries.Covalence;
using Oxide.Core.Plugins;
using Newtonsoft.Json;
using Oxide.Game.Rust.Cui;
using UnityEngine;
using UnityEngine.UI;

namespace Oxide.Plugins
{
    [Info("GiveMenu", "Flux", "1.6.0")]
    public class GiveMenu : RustPlugin
    {
        [PluginReference] private Plugin InfoToast;

        #region [Vars]

        [PluginReference] private Plugin ServerMenu;

        private const String Layer = "GiveMenu.Layer";
        private const String MainPanel = "GiveMenu.Main";
        private const String Permission = "GiveMenu.Permission";
        private const String AdminPermission = "GiveMenu.admin";
        private const String UnlimitedPermission = "GiveMenu.unlimited";
        private const String SiteUrl = "https://ice-rust.store/";
        private const String SiteQrPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAbwAAAG8CAIAAAAq0oeIAAAG0klEQVR42u3dy3HDQAxEwaHL+adMp6CDjMKnOwBZXJGv1gdwn/d9A8BnfiwBgGgCiCaAaAKIJoBoAoimJQAQTQDRBBBNANEEEE0A0bQEAKIJIJoAogkgmgCiCSCalgBANAFEE0A0AUQTQDQBRNMSAIgmgGgCiCaAaAKIJoBoWgIA0QQQTQDRBBBNANEEEE1LACCaAKIJIJoAogkgmgCiaQkARBNANAFEE0A0AXb67faFnuc5+2O87ztunT/5zp/8rW99Trfv7LmY9VzYaQKIJoBoAogmgGgCiCYAogkgmgCiCSCaAKIJELPnMYuaNbPDlbPe37quylnvrXPcngs7TQDRBBBNANEEQDQBRBNANAFEE0A0AUQTANEEyLHZ8zSbae028zvxLPKJZ7V3m6n3XNhpAogmgGgCiCYAogkgmgCiCSCaAKIJIJoAiCZAzJ6T/XPuaTZ/3e26sNMEEE0A0QQQTQBEE0A0AUQTQDQBRBNANAEQTYCYPT9j4tnflXPclfPgleuDnSaAaAKIJgCiCSCaAKIJIJoAogkgmgCIJoBoAsTsef5rtjqHzxnfOsO+dabec2GnCSCaAKIJgGgCiCaAaAKIJoBoAogmAKIJIJoAMXse5033PR+88nO2ro/nwk4TQDQBEE0A0QQQTQDRBBBNANEEQDQBRBNANAEaeJyDfHPuvnL+utvcdLfv7Bm00wQQTQBEE0A0AUQTQDQBRBNANAEQTQDRBBBNgJg9XzGjPXG2euIZ2ZX3arcZ/wx8n4CdJgCiCSCaAKIJIJoAogkgmgCIJoBoAogmgGgCxOy5Ge32c9OX55Qvn7E+8bq63WN2mgCiCSCaAKIJIJoAogmAaAKIJoBoAogmgGgCLPWbpWdbm2GPdwUsWudu63N5ne00AUQTQDQBRBNANAFEEwDRBBBNANEEEE0A0QSIc8/XndmdgTP1zgffd/9MvDe6Pe92mgD+PQcQTQDRBEA0AUQTQDQBRBNANAFEEwDRBIhzz7NnlrnycybOTXebK798j9lpAiCaAKIJIJoAogkgmgCiCYBoAogmgGgCiCZAzJ5nz1nbaTYLP/EM6G6/e+X9863fyzy4nSaAaAIgmgCiCSCaAKIJIJoAogmAaAKIJoBoAjTwmGXOujn3yrnpbn9r670x8V7tdr68nSaAaAKIJoBoAogmAKIJIJoAogkgmgCiCSCaAGTUuecpnGXu9jnO2p517RNn883d22kCiCaAaAKIJoBoAiCaAKIJIJoAogkgmgCiCUAWzZ53mx1O4cxv5bVPnHfe+re2Pqd2mgD+PQdANAFEE0A0AUQTQDQBRBMA0QQQTQDRBIjZ8+w5J3rrjG23+euJZ9lPvFfN1NtpAogmgGgCiCaAaAKIJgCiCSCaAKIJIJoAogkQs+e5NVfu/OtZ1zVxHnzrOxAuv5PBThNANAFEE0A0AUQTQDQBEE0A0QQQTQDRBBBNgKUeZ3Zn3fxst5nxyvnrbmesd1vnie9J6PYM2mkCiCaAaAKIJoBoAogmAKIJIJoAogkgmgCiCRDnnmfbbPXEc6Irr71yNr/bdXkubq6hnSaAaAKIJoBoAogmgGgCIJoAogkgmgCiCSCaAHHuOcfOau/2Od3OqZ+4PhPf29Btfew0AUQTQDQBRBNANAFEEwDRBBBNANEEEE0A0QSIc8+z7ezmibbOTVf+phPPau92XZW/6cR3X9hpAogmgGgCiCaAaAKIJgCiCSCaAKIJIJoAogkQs+fZNqOdgTP1E+eCneu9757/1n3o3HMA/54DIJoAogkgmgCiCSCaAKIJgGgCiCaAaALE7Hn2nJ++dS648nMqf9Nu886V9+rW9yTYaQKIJoBoAogmAKIJIJoAogkgmgCiCSCaAIgmQMyeH1M5q9ttLrjbTP3E9xJ0u3+6rY+dJoBoAogmgGgCiCaAaAIgmgCiCSCaAKIJIJoAMXvOIpfP7O42V97tXPit39lOE8C/5wCiCSCaAIgmgGgCiCaAaAKIJoBoAiCaADF7nj3nKU88+7vbnPsn36dybrrbtZsrt9MEEE0A0QQQTQDRBEA0AUQTQDQBRBNANAFEE4Asmj3vdtZ2Ds/vV54hXvl9Jt7PW2e97TQBRBNANAEQTQDRBBBNANEEEE0A0QRANAFEE6DEYxYVwE4TQDQBRBNANAFEE0A0LQGAaAKIJoBoAogmgGgCiKYlABBNANEEEE0A0QQQTQDRtAQAogkgmgCiCSCaAKIJIJqWAEA0AUQTQDQBRBNANAFE0xIAiCaAaAKIJoBoAogmgGhaAgDRBBBNANEEEE0A0QQQTUsAIJoAogkgmgCiCSCaAOf9ATUoK8M7IdcxAAAAAElFTkSuQmCC";
        private String _siteQrPng;

        private const String HostMain = "ServerMenu.UI.Main";
        private const String HostTab = "give";
        private const String HostTitle = "ВЫДАЧА";
        private const String HostLockedTitle = "ВЫДАЧА 🔒";
        private const String HostIcon = "assets/icons/crate.png";
        private const Int32 HostOrder = 40;
        private const Single HostHeightFallback = 584f;
        private const Single HostBottomPad = 24f;
        private const Single AdminBtnW = 210f;
        private const Single AdminBtnTop = 12f;

        private const String AdminLayer = "GiveMenu.Admin.Layer";
        private const String AdminMainPanel = "GiveMenu.Admin.Main";

        private class MenuState
        {
            public Int32 Category;
            public String Item = String.Empty;
            public Int32 Amount = 1;
            public String Search = String.Empty;
            public Int32 GridPage;
            public Boolean Hosted;
            public UInt64 UserId;
        }

        private readonly Dictionary<UInt64, MenuState> _menuStates = new();
        private readonly Dictionary<UInt64, MenuState> _adminMenuStates = new();
        private readonly HashSet<UInt64> _hostAdmin = new();
        private List<KeyValuePair<ItemCategory, List<ItemDefinition>>> _categories = new();
        private HashSet<String> _blacklist = new HashSet<String>(StringComparer.OrdinalIgnoreCase);
        private const String PersonalBlacklistFile = "GiveMenu/PlayerBlacklist";
        private Dictionary<UInt64, HashSet<String>> _personalBlacklist = new Dictionary<UInt64, HashSet<String>>();
        private const String PersonalAllowlistFile = "GiveMenu/PlayerAllowlist";
        private Dictionary<UInt64, HashSet<String>> _personalAllowlist = new Dictionary<UInt64, HashSet<String>>();

        private const String WipeDataFile = "GiveMenu/WipeState";
        private WipeState _wipeState = new WipeState();
        private PluginConfig _config;

        private class WipeState
        {
            public String SaveName = String.Empty;
            public Double UnlockAt;
            public Boolean Initialized;
                                                               
            public Int64 SaveCreatedUnix;
        }

        private class PluginConfig
        {
            [JsonProperty("Заголовок панели «нет доступа»")]
            public String NoAccessTitle = "Нет доступа";

            [JsonProperty("Текст панели «нет доступа»")]
            public String NoAccessText =
                "Доступ к выдаче предметов можно купить на сайте или у разработчика проекта";

            [JsonProperty("Контакты в панели «нет доступа»", ObjectCreationHandling = ObjectCreationHandling.Replace)]
            public List<String> Contacts = new List<String>
            {
                "Discord: .f1ux",
                "Telegram: @f1ux_69",
                "ice-rust.store"
            };

            [JsonProperty("КД меню выдачи после вайпа, часы")]
            public Double WipeCooldownHours = 2d;

            [JsonProperty("Лимит по умолчанию для новой категории")]
            public Int32 DefaultCategoryLimit = 500000;

            [JsonProperty("Лимиты выдачи по категориям (0 = без лимита)")]
            public Dictionary<String, Int32> CategoryLimits =
                new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase)
                {
                    ["Weapon"] = 500000,
                    ["Ammunition"] = 500000,
                    ["Attire"] = 500000,
                    ["Tool"] = 500000,
                    ["Medical"] = 500000,
                    ["Food"] = 500000,
                    ["Resources"] = 500000,
                    ["Component"] = 500000,
                    ["Construction"] = 500000,
                    ["Electrical"] = 500000,
                    ["Traps"] = 500000,
                    ["Fun"] = 500000,
                    ["Items"] = 500000,
                    ["Misc"] = 500000
                };

            [JsonProperty("Лимиты выдачи по предметам, ключ — shortname (0 = без лимита; предмета нет в списке — лимита нет)")]
            public Dictionary<String, Int32> ItemLimits =
                new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);

            [JsonProperty("Blacklist предметов (shortname)")]
            public List<String> Blacklist = new List<String>
            {
                "ammo.snowballgun",
                "blueprintbase",
                "rhib",
                "spraycandecal",
                "vehicle.chassis",
                "vehicle.module",
                "water",
                "water.salt"
            };
        }



        public static class UiStyle
        {
            public const string Version = "2.0";

            public const string Bold = "robotocondensed-bold.ttf";
            public const string Reg = "robotocondensed-regular.ttf";

            public const string MatBlur = "assets/content/ui/uibackgroundblur.mat";
            public const string SpriteRadial = "assets/content/ui/ui.background.transparent.radial.psd";
            public const string IconLock = "assets/icons/bp-lock.png";

            public const string ColOverlay = "0 0 0 0.97";
            public const string ColBackdropBlur = "0 0 0 0.23";
            public const string ColBackdropRadial = "#36363699";

            public const string ColHeader = "1 1 1 0.045";
            public const string ColMain = "1 1 1 0.025";
            public const string ColCard = "1 1 1 0.045";
            public const string ColCardDark = "0 0 0 0.16";
            public const string ColInput = "0 0 0 0.16";
            public const string ColNav = "1 1 1 0.025";
            public const string ColLine = "1 1 1 0.045";
            public const string ColSoft = "0 0 0 0.16";
            public const string ColPremiumSoft = "1 1 1 0.045";

            public const string ColSep = "1 1 1 0.07";
            public const string ColSepSoft = "1 1 1 0.06";
            public const string ColTrack = "0 0 0 0.32";
            public const string ColTrackFrame = "#B2A9A366";

            public const string ColGreenBtn = "#65A30DBF";
            public const string ColGreenTxt = "#FFFFFF";
            public const string ColRedBtn = "#E0947A73";
            public const string ColRedStrong = "#E0947ABF";
            public const string ColRedText = "#E0947A";
            public const string ColSecondaryBtn = "#B2A9A3E6";
            public const string ColSecondaryBtnDisabled = "#B2A9A373";
            public const string ColSecondaryText = "#45403B";

            public const string ColText = "#CEC5BB";
            public const string ColSubText = "#A39C96";
            public const string ColMuted = "#7F7D7D";
            public const string ColPremium = "#CEC5BB";
            public const string ColWhite = "#FFFFFF";
            public const string ColClear = "0 0 0 0";
            public const string ColTint = "1 1 1 1";
            public const string ColDimmed = "1 1 1 0.3";

            public const int SizeAppTitle = 23;
            public const int SizeHeading = 24;
            public const int SizeClose = 20;
            public const int SizeSectionValue = 22;
            public const int SizeCardValue = 20;
            public const int SizeInfoValue = 16;
            public const int SizeCardTitle = 15;
            public const int SizeRowTitle = 14;
            public const int SizeTab = 13;
            public const int SizeSection = 13;
            public const int SizeBody = 12;
            public const int SizeButton = 12;
            public const int SizeSmall = 11;
            public const int SizeCaption = 10;
            public const int SizeTiny = 9;

            public const float WinW = 1120f;
            public const float HeaderTop = 360f;
            public const float HeaderBottom = 312f;
            public const float TabsTop = 304f;
            public const float TabsBottom = 264f;
            public const float MainTop = 256f;
            public const float MainBottom = -328f;

            public const float TabW = 148f;
            public const float TabGap = 6f;
            public const float TabAccentH = 3f;
            public const float TabAccentInset = 12f;
            public const float CloseW = 48f;

            public const float PadX = 22f;
            public const float PadCard = 16f;
            public const float PadCardWide = 18f;
            public const float PadRow = 14f;

            public const float AccentThin = 3f;
            public const float AccentMed = 4f;
            public const float AccentWide = 5f;

            public const float HeadingTop = -8f;
            public const float HeadingBottom = -40f;
            public const float HeadingSubBottom = -64f;
            public const float HeadingLine = -72f;
            public const float ContentTop = -84f;

            public const float RowAction = 46f;
            public const float RowCommand = 38f;
            public const float RowHome = 48f;
            public const float RowStat = 24f;
            public const float BtnH = 32f;
            public const float BtnW = 126f;

            public const float NavIcon = 16f;
            public const float NavCountW = 44f;
            public const float SlotInset = 6f;
            public const float CounterValueW = 56f;

            public const string FxClick = "assets/bundled/prefabs/fx/notice/item.select.fx.prefab";
            public const string FxApplied = "assets/bundled/prefabs/fx/notice/stack.world.fx.prefab";
            public const string FxSkinChange = "assets/prefabs/deployable/repair bench/effects/skinchange_spraypaint.prefab";

            public static void Sound(BasePlayer player, string prefab)
            {
                if (player == null || player.net == null || player.net.connection == null) return;
                if (string.IsNullOrEmpty(prefab)) return;

                EffectNetwork.Send(new Effect(prefab, player, 0u, Vector3.zero, Vector3.zero), player.net.connection);
            }

            public const float LockIcon = 40f;
            public const float LockContactRow = 24f;
            public const float LockW = 520f;
            public const float LockH = 320f;

            public static void LockedPanel(CuiElementContainer c, string parent, string name,
                string title, string text, string[] contacts, string qrPng = null, string qrUrl = null)
            {
                if (!string.IsNullOrEmpty(qrPng))
                {
                    StoreAccessPanel(c, parent, name, title, text, contacts, qrPng, qrUrl);
                    return;
                }

                Panel(c, parent, name, "0.5 0.5", "0.5 0.5",
                    Off(-LockW / 2f, -LockH / 2f), Off(LockW / 2f, LockH / 2f), ColCard);

                Panel(c, name, name + ".Accent", "0 1", "1 1", "0 -2", "0 0", ColRedStrong);

                float y = -PadCardWide - 8f;

                Sprite(c, name, "0.5 1", "0.5 1",
                    Off(-LockIcon / 2f, y - LockIcon), Off(LockIcon / 2f, y),
                    IconLock, ColRedText);

                y -= LockIcon + 16f;

                Label(c, name, "0 1", "1 1", Off(PadCardWide, y - 30f), Off(-PadCardWide, y),
                    title, SizeSectionValue, ColText, TextAnchor.MiddleCenter, Bold);

                y -= 38f;

                Label(c, name, "0 1", "1 1", Off(PadCardWide, y - 48f), Off(-PadCardWide, y),
                    text, SizeBody, ColSubText, TextAnchor.UpperCenter, Reg);

                y -= 60f;

                if (contacts == null) return;

                for (int i = 0; i < contacts.Length; i++)
                {
                    if (string.IsNullOrEmpty(contacts[i])) continue;

                    Label(c, name, "0 1", "1 1",
                        Off(PadCardWide, y - LockContactRow), Off(-PadCardWide, y),
                        contacts[i], SizeCardTitle, ColText, TextAnchor.MiddleCenter, Bold);

                    y -= LockContactRow;
                }
            }

            public static void StoreAccessPanel(CuiElementContainer c, string parent, string name,
                string title, string text, string[] contacts, string qrPng, string qrUrl)
            {
                const float width = 720f;
                const float height = 380f;
                const float qrSize = 198f;
                const float qrLeft = 486f;
                const float qrTop = -110f;

                Panel(c, parent, name, "0.5 0.5", "0.5 0.5",
                    Off(-width / 2f, -height / 2f), Off(width / 2f, height / 2f), ColCard);
                Panel(c, name, name + ".Accent", "0 1", "1 1", "0 -2", "0 0", ColRedStrong);

                Sprite(c, name, "0 1", "0 1", "28 -64", "62 -30", IconLock, ColRedText);
                Label(c, name, "0 1", "1 1", "82 -66", "-28 -28",
                    title, SizeSectionValue, ColText, TextAnchor.MiddleLeft, Bold);
                Panel(c, name, name + ".Divider", "0 1", "1 1", "28 -85", "-28 -84", ColSep);

                Label(c, name, "0 1", "0 1", "28 -164", "430 -110",
                    text, SizeTab, ColSubText, TextAnchor.UpperLeft, Reg);
                Label(c, name, "0 1", "0 1", "28 -190", "430 -170",
                    "Контакты проекта", SizeSmall, ColSubText, TextAnchor.MiddleLeft, Reg);

                float y = -200f;
                if (contacts != null)
                {
                    foreach (string contact in contacts.Where(x => !string.IsNullOrWhiteSpace(x)).Take(6))
                    {
                        Label(c, name, "0 1", "0 1", Off(28f, y - 26f), Off(430f, y),
                            contact, SizeInfoValue, ColText, TextAnchor.MiddleLeft, Bold);
                        y -= 26f;
                    }
                }

                Panel(c, name, name + ".Columns", "0 1", "0 1", "456 -308", "457 -110", ColSep);
                string frame = name + ".QrFrame";
                Panel(c, name, frame, "0 1", "0 1",
                    Off(qrLeft, qrTop - qrSize), Off(qrLeft + qrSize, qrTop), ColWhite);
                c.Add(new CuiElement
                {
                    Name = name + ".Qr",
                    Parent = frame,
                    Components =
                    {
                        new CuiRawImageComponent { Png = qrPng, Color = ColTint, BlocksRaycast = false },
                        new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1",
                            OffsetMin = "0 0", OffsetMax = "0 0" }
                    }
                });

                string address = string.IsNullOrWhiteSpace(qrUrl) ? "ice-rust.store" : qrUrl;
                Uri uri;
                if (Uri.TryCreate(address, UriKind.Absolute, out uri))
                    address = uri.Host;
                Label(c, name, "0 1", "0 1", "476 -340", "694 -316",
                    address, SizeCardTitle, ColText, TextAnchor.MiddleCenter, Bold);
                Label(c, name, "0 1", "0 1", "476 -358", "694 -340",
                    "Наведите камеру телефона", SizeSmall, ColSubText, TextAnchor.MiddleCenter, Reg);
            }

            public static string F(float value)
            {
                return value.ToString("0.###", CultureInfo.InvariantCulture);
            }

            public static string Off(float x, float y)
            {
                return F(x) + " " + F(y);
            }

            private static readonly Dictionary<string, string> colCache = new Dictionary<string, string>();

            public static string Col(string hex)
            {
                if (string.IsNullOrEmpty(hex)) return ColUncached(hex);
                string cached;
                if (colCache.TryGetValue(hex, out cached)) return cached;
                cached = ColUncached(hex);
                if (colCache.Count < 1024) colCache[hex] = cached;
                return cached;
            }

            private static string ColUncached(string hex)
            {
                if (string.IsNullOrEmpty(hex)) return "1 1 1 1";
                if (!hex.StartsWith("#", StringComparison.Ordinal)) return hex;
                Color color;
                return ColorUtility.TryParseHtmlString(hex, out color)
                    ? F(color.r) + " " + F(color.g) + " " + F(color.b) + " " + F(color.a)
                    : "1 1 1 1";
            }

            public static bool Frosted(string color)
            {
                return color == ColHeader || color == ColMain || color == ColCard || color == ColNav ||
                       color == ColPremiumSoft || color == ColLine;
            }

            public static void Panel(CuiElementContainer c, string parent, string name,
                string amin, string amax, string omin, string omax, string color)
            {
                CuiImageComponent image = new CuiImageComponent { Color = Col(color) };
                if (Frosted(color)) image.Material = MatBlur;
                if (color == ColClear) image.BlocksRaycast = false;

                c.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax },
                    Image = image
                }, parent, name, name);
            }

            public static void Label(CuiElementContainer c, string parent,
                string amin, string amax, string omin, string omax,
                string text, int size = SizeTab, string color = ColText,
                TextAnchor align = TextAnchor.MiddleLeft, string font = Bold)
            {
                c.Add(new CuiLabel
                {
                    RectTransform = { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax },
                    Text =
                    {
                        Text = text,
                        Font = font,
                        FontSize = size,
                        Align = align,
                        Color = Col(color),
                        BlocksRaycast = false
                    }
                }, parent);
            }

            public static void Button(CuiElementContainer c, string parent,
                string amin, string amax, string omin, string omax,
                string command, string text, string color,
                int size = SizeTab, string textColor = ColText, string close = null)
            {
                CuiButton button = new CuiButton
                {
                    RectTransform = { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax },
                    Button = { Color = Col(color), Command = command ?? string.Empty },
                    Text = { Text = text, Font = Bold, FontSize = size, Align = TextAnchor.MiddleCenter, Color = Col(textColor) }
                };

                if (color != ColClear) button.Button.Material = MatBlur;
                if (!string.IsNullOrEmpty(close)) button.Button.Close = close;

                c.Add(button, parent);
            }

            public static void Primary(CuiElementContainer c, string parent,
                string amin, string amax, string omin, string omax, string command, string text, bool enabled)
            {
                Button(c, parent, amin, amax, omin, omax, enabled ? command : string.Empty, text,
                    enabled ? ColGreenBtn : ColSecondaryBtnDisabled, SizeButton,
                    enabled ? ColGreenTxt : ColMuted);
            }

            public static void Secondary(CuiElementContainer c, string parent,
                string amin, string amax, string omin, string omax, string command, string text, bool enabled)
            {
                Button(c, parent, amin, amax, omin, omax, enabled ? command : string.Empty, text,
                    enabled ? ColSecondaryBtn : ColSecondaryBtnDisabled, SizeButton,
                    enabled ? ColSecondaryText : ColMuted);
            }

            public static void Danger(CuiElementContainer c, string parent,
                string amin, string amax, string omin, string omax, string command, string text, bool enabled)
            {
                Button(c, parent, amin, amax, omin, omax, enabled ? command : string.Empty, text,
                    enabled ? ColRedStrong : ColSecondaryBtnDisabled, SizeButton,
                    enabled ? ColGreenTxt : ColMuted);
            }

            public static void Hit(CuiElementContainer c, string parent, string command)
            {
                Button(c, parent, "0 0", "1 1", "0 0", "0 0", command, string.Empty, ColClear);
            }

            public static void Backdrop(CuiElementContainer c, string parent, string name)
            {
                c.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" },
                    Image = { Color = ColOverlay }
                }, parent, name, name);

                c.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" },
                    Image = { Color = ColBackdropBlur, Material = MatBlur }
                }, name, name + ".Blur");

                c.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" },
                    Image = { Sprite = SpriteRadial, Color = Col(ColBackdropRadial) }
                }, name, name + ".Radial");
            }

            public static void Window(CuiElementContainer c, string parent, string root)
            {
                c.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" },
                    Image = { Color = ColClear },
                    CursorEnabled = true
                }, parent, root, root);

                Backdrop(c, root, root + ".Backdrop");
            }

            public static void Header(CuiElementContainer c, string root, string name,
                string title, string meta, string closeCommand, string closeLayer)
            {
                Panel(c, root, name, "0.5 0.5", "0.5 0.5",
                    Off(-WinW / 2f, HeaderBottom), Off(WinW / 2f, HeaderTop), ColHeader);

                Panel(c, name, name + ".Accent", "0 0", "0 1", "0 0", Off(AccentThin, 0f), ColText);

                Label(c, name, "0 0", "0.46 1", Off(PadX - 2f, 0f), "0 0",
                    title, SizeAppTitle, ColText, TextAnchor.MiddleLeft, Bold);

                Label(c, name, "0.46 0", "1 1", "0 0", Off(-CloseW - PadCard + 4f, 0f),
                    meta, SizeSmall, ColMuted, TextAnchor.MiddleRight, Reg);

                Button(c, name, "1 0", "1 1", Off(-CloseW, 0f), "0 0",
                    closeCommand, "×", ColRedBtn, SizeClose, ColRedText, closeLayer);
            }

            public static string Body(CuiElementContainer c, string root, string name, float top, float bottom)
            {
                Panel(c, root, name, "0.5 0.5", "0.5 0.5",
                    Off(-WinW / 2f, bottom), Off(WinW / 2f, top), ColMain);
                return name;
            }


            public static string CategoryIcon(string category)
            {
                switch (category)
                {
                    case "Weapon": return "assets/icons/weapon.png";
                    case "Ammunition": return "assets/icons/ammunition.png";
                    case "Attire": return "assets/icons/clothing.png";
                    case "Tool": return "assets/icons/tools.png";
                    case "Medical": return "assets/icons/medical.png";
                    case "Food": return "assets/icons/cooking.png";
                    case "Resources": return "assets/icons/resource.png";
                    case "Component": return "assets/icons/gear.png";
                    case "Construction": return "assets/icons/construction.png";
                    case "Electrical": return "assets/icons/electric.png";
                    case "Traps": return "assets/icons/traps.png";
                    case "Fun": return "assets/icons/fun.png";
                    case "Items": return "assets/icons/crate.png";
                    case "Favourite": return "assets/icons/star.png";
                    case "All": return "assets/icons/inventory.png";
                    case "Common": return "assets/icons/inventory.png";
                    default: return "assets/icons/menu_dots.png";
                }
            }

            public static string Strip(CuiElementContainer c, string root, string name)
            {
                Panel(c, root, name, "0.5 0.5", "0.5 0.5",
                    Off(-WinW / 2f, TabsBottom), Off(WinW / 2f, TabsTop), ColHeader);
                return name;
            }

            public static void Tab(CuiElementContainer c, string strip, string name,
                float x1, float x2, string title, string command, bool active)
            {
                Panel(c, strip, name, "0 0", "0 1", Off(x1, 0f), Off(x2, 0f), ColClear);

                Label(c, name, "0 0", "1 1", "0 0", "0 0",
                    title, SizeTab, active ? ColWhite : ColMuted, TextAnchor.MiddleCenter, Bold);

                if (active)
                    Panel(c, name, name + ".Active", "0 0", "1 0",
                        Off(TabAccentInset, 0f), Off(-TabAccentInset, TabAccentH), ColGreenBtn);

                Button(c, name, "0 0", "1 1", "0 0", "0 0", command, string.Empty, ColClear);
            }

            public static void Tabs(CuiElementContainer c, string strip, string prefix,
                string[] keys, string[] titles, string active, string command)
            {
                float total = keys.Length * TabW + (keys.Length - 1) * TabGap;
                float start = (WinW - total) * 0.5f;

                for (int i = 0; i < keys.Length; i++)
                {
                    float x1 = start + i * (TabW + TabGap);
                    Tab(c, strip, prefix + "." + keys[i], x1, x1 + TabW,
                        titles[i], command + keys[i], active == keys[i]);
                }
            }

            public static void Heading(CuiElementContainer c, string main, string title, string subtitle)
            {
                Label(c, main, "0 1", "1 1", Off(PadX, HeadingBottom), Off(-PadX, HeadingTop),
                    title, SizeHeading, ColText, TextAnchor.MiddleLeft, Bold);

                if (!string.IsNullOrEmpty(subtitle))
                    Label(c, main, "0 1", "1 1", Off(PadX, HeadingSubBottom), Off(-PadX, HeadingBottom + 2f),
                        subtitle, SizeBody, ColSubText, TextAnchor.MiddleLeft, Reg);

                Panel(c, main, main + ".HeadingLine", "0 1", "1 1",
                    Off(PadX, HeadingLine), Off(-PadX, HeadingLine + 1f), ColSep);
            }

            public static void Section(CuiElementContainer c, string parent,
                float left, float right, float y, string title)
            {
                Label(c, parent, "0 1", "0 1", Off(left, y - RowStat), Off(right, y),
                    title, SizeSection, ColMuted, TextAnchor.MiddleLeft, Bold);
            }

            public static string NavItem(CuiElementContainer c, string parent, string name,
                float left, float right, float top, float height,
                string icon, string title, string count, bool active, string command)
            {
                Panel(c, parent, name, "0 1", "0 1", Off(left, top - height), Off(right, top),
                    active ? ColCard : ColClear);

                if (active)
                    Panel(c, name, name + ".Accent", "0 0", "0 1", "0 0", Off(AccentThin, 0f), ColGreenBtn);

                float textLeft = PadRow;

                if (!string.IsNullOrEmpty(icon))
                {
                    Sprite(c, name, "0 0.5", "0 0.5", Off(PadRow, -NavIcon / 2f), Off(PadRow + NavIcon, NavIcon / 2f),
                        icon, active ? ColText : ColMuted);
                    textLeft = PadRow + NavIcon + 10f;
                }

                Label(c, name, "0 0", "1 1", Off(textLeft, 0f), Off(-NavCountW, 0f),
                    title, SizeBody, active ? ColWhite : ColMuted, TextAnchor.MiddleLeft, Bold);

                if (!string.IsNullOrEmpty(count))
                    Label(c, name, "1 0", "1 1", Off(-NavCountW, 0f), Off(-PadRow, 0f),
                        count, SizeSmall, active ? ColText : ColMuted, TextAnchor.MiddleRight, Bold);

                Hit(c, name, command);
                return name;
            }

            public static string Card(CuiElementContainer c, string parent, string name,
                float left, float right, float top, float bottom, string fill, string accent)
            {
                Panel(c, parent, name, "0 1", "0 1", Off(left, bottom), Off(right, top), fill);

                if (!string.IsNullOrEmpty(accent))
                    Panel(c, name, name + ".Accent", "0 0", "0 1", "0 0", Off(AccentThin, 0f), accent);

                return name;
            }

            public static void Sprite(CuiElementContainer c, string parent,
                string amin, string amax, string omin, string omax, string sprite, string tint)
            {
                c.Add(new CuiElement
                {
                    Parent = parent,
                    Components =
                    {
                        new CuiImageComponent { Sprite = sprite, Color = Col(tint), BlocksRaycast = false },
                        new CuiRectTransformComponent { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax }
                    }
                });
            }

            public static void ItemIcon(CuiElementContainer c, string parent,
                string amin, string amax, string omin, string omax, int itemId, ulong skinId, string tint)
            {
                c.Add(new CuiElement
                {
                    Parent = parent,
                    Components =
                    {
                        new CuiImageComponent { ItemId = itemId, SkinId = skinId, Color = Col(tint), BlocksRaycast = false },
                        new CuiRectTransformComponent { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax }
                    }
                });
            }

            public static string Slot(CuiElementContainer c, string parent, string name,
                float left, float right, float top, float bottom,
                int itemId, ulong skinId, string caption,
                bool active, bool dimmed, bool locked, string command)
            {
                Panel(c, parent, name, "0 1", "0 1", Off(left, bottom), Off(right, top),
                    active ? ColCard : ColCardDark);

                if (active)
                    Panel(c, name, name + ".Accent", "0 0", "1 0", "0 0", Off(0f, AccentThin), ColGreenBtn);

                ItemIcon(c, name, "0 0", "1 1", Off(SlotInset, SlotInset), Off(-SlotInset, -SlotInset),
                    itemId, skinId, dimmed ? ColDimmed : ColTint);

                if (locked)
                    Sprite(c, name, "0.5 0.5", "0.5 0.5", "-11 -11", "11 11", IconLock, ColRedText);

                if (!string.IsNullOrEmpty(caption))
                    Label(c, name, "0 0", "1 0", Off(0f, SlotInset), Off(-SlotInset, SlotInset + 14f),
                        caption, SizeTiny, ColSubText, TextAnchor.LowerRight, Bold);

                Hit(c, name, command);
                return name;
            }

            public static void Badge(CuiElementContainer c, string parent, string name,
                string amin, string amax, string omin, string omax, string text, string fill, string textColor)
            {
                Panel(c, parent, name, amin, amax, omin, omax, fill);
                Label(c, name, "0 0", "1 1", Off(8f, 0f), Off(-8f, 0f),
                    text, SizeTiny, textColor, TextAnchor.MiddleCenter, Bold);
            }

            public static void Progress(CuiElementContainer c, string parent, string name,
                string amin, string amax, string omin, string omax, float fraction)
            {
                Panel(c, parent, name, amin, amax, omin, omax, ColTrackFrame);
                Panel(c, name, name + ".Track", "0 0", "1 1", "2 2", "-2 -2", ColTrack);

                float clamped = fraction < 0f ? 0f : fraction > 1f ? 1f : fraction;
                if (clamped <= 0f) return;

                Panel(c, name, name + ".Fill", "0 0", F(clamped) + " 1", "2 2", "-2 -2", ColGreenBtn);
            }

            public static void Toast(CuiElementContainer c, string parent, string name,
                float left, float right, float top, float bottom,
                string title, string body, string accent)
            {
                Card(c, parent, name, left, right, top, bottom, ColCardDark, accent);

                Label(c, name, "0 1", "1 1", Off(PadRow, -28f), Off(-PadRow, -8f),
                    title, SizeCardTitle, ColText, TextAnchor.MiddleLeft, Bold);

                Label(c, name, "0 0", "1 1", Off(PadRow, 10f), Off(-PadRow, -30f),
                    body, SizeSmall, ColSubText, TextAnchor.UpperLeft, Reg);
            }

            public static void Input(CuiElementContainer c, string parent, string name,
                float left, float right, float top, float bottom,
                string text, string placeholder, string command, int limit)
            {
                Panel(c, parent, name, "0 1", "0 1", Off(left, bottom), Off(right, top), ColInput);
                InputInto(c, name, text, placeholder, command, limit);
            }

            public static void InputBottom(CuiElementContainer c, string parent, string name,
                float left, float right, float bottom, float height,
                string text, string placeholder, string command, int limit)
            {
                Panel(c, parent, name, "0 0", "0 0", Off(left, bottom), Off(right, bottom + height), ColInput);
                InputInto(c, name, text, placeholder, command, limit);
            }

            private static void InputInto(CuiElementContainer c, string name,
                string text, string placeholder, string command, int limit)
            {
                string hint = name + ".Hint";

                if (string.IsNullOrEmpty(text))
                    c.Add(new CuiLabel
                    {
                        RectTransform =
                        {
                            AnchorMin = "0 0",
                            AnchorMax = "1 1",
                            OffsetMin = Off(PadRow, 0f),
                            OffsetMax = Off(-PadRow, 0f)
                        },
                        Text =
                        {
                            Text = placeholder,
                            Font = Reg,
                            FontSize = SizeBody,
                            Align = TextAnchor.MiddleLeft,
                            Color = Col(ColMuted),
                            BlocksRaycast = false
                        }
                    }, name, hint);

                c.Add(new CuiElement
                {
                    Parent = name,
                    Components =
                    {
                        new CuiInputFieldComponent
                        {
                            Text = text,
                            FontSize = SizeBody,
                            Font = Reg,
                            Color = Col(ColText),
                            Align = TextAnchor.MiddleLeft,
                            CharsLimit = limit,
                            Command = command,
                            LineType = UnityEngine.UI.InputField.LineType.SingleLine,
                            NeedsKeyboard = true,
                            PlaceholderId = hint
                        },
                        new CuiRectTransformComponent
                        {
                            AnchorMin = "0 0",
                            AnchorMax = "1 1",
                            OffsetMin = Off(PadRow, 0f),
                            OffsetMax = Off(-PadRow, 0f)
                        }
                    }
                });
            }

            public static void Counter(CuiElementContainer c, string parent, string name,
                float left, float right, float top, float height,
                int value, bool canDown, bool canUp, string command, string maxCaption,
                string inputCommand = null, int inputLimit = 7)
            {
                Panel(c, parent, name, "0 1", "0 1", Off(left, top - height), Off(right, top), ColClear);
                CounterInto(c, name, right - left, height, value, canDown, canUp, command, maxCaption,
                    inputCommand, inputLimit);
            }

            public static void CounterBottom(CuiElementContainer c, string parent, string name,
                float left, float right, float bottom, float height,
                int value, bool canDown, bool canUp, string command, string maxCaption,
                string inputCommand = null, int inputLimit = 7)
            {
                Panel(c, parent, name, "0 0", "0 0", Off(left, bottom), Off(right, bottom + height), ColClear);
                CounterInto(c, name, right - left, height, value, canDown, canUp, command, maxCaption,
                    inputCommand, inputLimit);
            }

            private static void CounterInto(CuiElementContainer c, string name,
                float width, float height,
                int value, bool canDown, bool canUp, string command, string maxCaption,
                string inputCommand, int inputLimit)
            {
                float step = height;
                float maxW = string.IsNullOrEmpty(maxCaption) ? 0f : 56f;
                float fieldW = width - step * 2f - maxW - TabGap * (maxW > 0f ? 3f : 2f);

                Secondary(c, name, "0 0", "0 1", "0 0", Off(step, 0f), command + " -1", "−", canDown);

                Panel(c, name, name + ".Value", "0 0", "0 1",
                    Off(step + TabGap, 0f), Off(step + TabGap + fieldW, 0f), ColInput);

                if (string.IsNullOrEmpty(inputCommand))
                {
                    Label(c, name + ".Value", "0 0", "1 1", "0 0", "0 0",
                        value.ToString(CultureInfo.InvariantCulture), SizeRowTitle, ColText,
                        TextAnchor.MiddleCenter, Bold);
                }
                else
                {
                    c.Add(new CuiElement
                    {
                        Parent = name + ".Value",
                        Components =
                        {
                            new CuiInputFieldComponent
                            {
                                Text = value.ToString(CultureInfo.InvariantCulture),
                                FontSize = SizeRowTitle,
                                Font = Bold,
                                Color = Col(ColText),
                                Align = TextAnchor.MiddleCenter,
                                CharsLimit = inputLimit,
                                Command = inputCommand,
                                LineType = UnityEngine.UI.InputField.LineType.SingleLine,
                                NeedsKeyboard = true
                            },
                            new CuiRectTransformComponent
                            {
                                AnchorMin = "0 0",
                                AnchorMax = "1 1",
                                OffsetMin = "4 0",
                                OffsetMax = "-4 0"
                            }
                        }
                    });
                }

                float plus = step + TabGap * 2f + fieldW;
                Secondary(c, name, "0 0", "0 1", Off(plus, 0f), Off(plus + step, 0f), command + " 1", "+", canUp);

                if (maxW <= 0f) return;

                float max = plus + step + TabGap;
                Secondary(c, name, "0 0", "0 1", Off(max, 0f), Off(max + maxW, 0f), command + " max", maxCaption, canUp);
            }

            public static string TableHead(CuiElementContainer c, string parent, string name,
                float left, float right, float top, float height,
                float[] edges, string[] titles, int sorted, bool descending, string command)
            {
                Panel(c, parent, name, "0 1", "0 1", Off(left, top - height), Off(right, top), ColCardDark);

                for (int i = 0; i < titles.Length && i + 1 < edges.Length; i++)
                {
                    string cell = name + ".C" + i.ToString(CultureInfo.InvariantCulture);
                    Panel(c, name, cell, "0 0", "0 1", Off(edges[i], 0f), Off(edges[i + 1], 0f), ColClear);

                    Label(c, cell, "0 0", "1 1", Off(PadRow, 0f), Off(-PadRow, 0f),
                        i == sorted ? titles[i] + (descending ? " ▼" : " ▲") : titles[i],
                        SizeSmall, i == sorted ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);

                    if (!string.IsNullOrEmpty(command))
                        Hit(c, cell, command + i.ToString(CultureInfo.InvariantCulture));
                }

                return name;
            }

            public static string TableRow(CuiElementContainer c, string parent, string name,
                float left, float right, float top, float height, bool odd)
            {
                Panel(c, parent, name, "0 1", "0 1", Off(left, top - height), Off(right, top),
                    odd ? ColCard : ColCardDark);

                Panel(c, name, name + ".Line", "0 0", "1 0", "0 0", "0 1", ColSepSoft);
                return name;
            }

            public static void TableCell(CuiElementContainer c, string row, float[] edges, int column,
                string text, int size, string color, TextAnchor align, string font)
            {
                Label(c, row, "0 0", "0 1", Off(edges[column] + PadRow, 0f), Off(edges[column + 1] - PadRow, 0f),
                    text, size, color, align, font);
            }
        }

        private Dictionary<ItemCategory, List<ItemDefinition>> m_ItemDefinitionsPerCategory = new();

        #endregion

        #region [Config]

        protected override void LoadDefaultConfig()
        {
            _config = new PluginConfig();
            SaveConfig();
        }

        protected override void LoadConfig()
        {
            base.LoadConfig();

            try
            {
                _config = Config.ReadObject<PluginConfig>();
                if (_config == null)
                    throw new Exception("Config is null");
            }
            catch (Exception e)
            {
                PrintWarning($"Ошибка чтения конфига GiveMenu: {e.Message}. Создан новый конфиг.");
                _config = new PluginConfig();
            }

            NormalizeConfig();
            SaveConfig();
        }

        protected override void SaveConfig()
        {
            Config.WriteObject(_config, true);
        }

        private void NormalizeConfig()
        {
            if (_config == null)
                _config = new PluginConfig();

            if (_config.WipeCooldownHours < 0d)
                _config.WipeCooldownHours = 0d;

            if (_config.DefaultCategoryLimit < 0)
                _config.DefaultCategoryLimit = 0;

            if (_config.CategoryLimits == null)
                _config.CategoryLimits = new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);

                                                                                            
            if (_config.ItemLimits == null)
                _config.ItemLimits = new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);

            _config.ItemLimits = new Dictionary<String, Int32>(
                _config.ItemLimits
                    .Where(x => !String.IsNullOrWhiteSpace(x.Key))
                    .ToDictionary(x => x.Key.Trim(), x => Math.Max(0, x.Value)),
                StringComparer.OrdinalIgnoreCase);

            _config.CategoryLimits = new Dictionary<String, Int32>(
                _config.CategoryLimits
                    .Where(x => !String.IsNullOrWhiteSpace(x.Key))
                    .ToDictionary(
                        x => x.Key.Trim(),
                        x => Math.Max(0, x.Value),
                        StringComparer.OrdinalIgnoreCase),
                StringComparer.OrdinalIgnoreCase);

            if (String.IsNullOrWhiteSpace(_config.NoAccessTitle))
                _config.NoAccessTitle = "Нет доступа";

            if (String.IsNullOrWhiteSpace(_config.NoAccessText))
                _config.NoAccessText =
                    "Доступ к выдаче предметов можно купить на сайте или у разработчика проекта";

            if (_config.Contacts == null)
                _config.Contacts = new List<String>();

            _config.Contacts = _config.Contacts
                .Where(x => !String.IsNullOrWhiteSpace(x))
                .Select(x => x.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();

            if (_config.Blacklist == null)
                _config.Blacklist = new List<String>();

            _config.Blacklist = _config.Blacklist
                .Where(x => !String.IsNullOrWhiteSpace(x))
                .Select(x => x.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(x => x, StringComparer.OrdinalIgnoreCase)
                .ToList();

            RebuildBlacklist();
        }

        private void RebuildBlacklist()
        {
            _blacklist = new HashSet<String>(
                (_config?.Blacklist ?? new List<String>())
                    .Where(x => !String.IsNullOrWhiteSpace(x))
                    .Select(x => x.Trim()),
                StringComparer.OrdinalIgnoreCase);
        }

        #endregion

        #region [Player-Data]

        private Dictionary<UInt64, PlayerData> _playerList = new();

        private class PlayerData
        {
            public Dictionary<String, Int32> CategoryAmounts =
                new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);

            public Dictionary<String, Int32> ItemAmounts =
                new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);
        }

        private PlayerData GetPlayerData(UInt64 member)
        {
            if (!_playerList.TryGetValue(member, out PlayerData data) || data == null)
            {
                data = new PlayerData();
                _playerList[member] = data;
            }

            if (data.CategoryAmounts == null)
                data.CategoryAmounts = new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);
            else if (!(data.CategoryAmounts.Comparer?.Equals(StringComparer.OrdinalIgnoreCase) ?? false))
                data.CategoryAmounts = new Dictionary<String, Int32>(
                    data.CategoryAmounts,
                    StringComparer.OrdinalIgnoreCase);

            if (data.ItemAmounts == null)
                data.ItemAmounts = new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);
            else if (!(data.ItemAmounts.Comparer?.Equals(StringComparer.OrdinalIgnoreCase) ?? false))
                data.ItemAmounts = new Dictionary<String, Int32>(
                    data.ItemAmounts,
                    StringComparer.OrdinalIgnoreCase);

            return data;
        }

        private Timer _playerSaveTimer;
        private PlayerSaveJob _playerSaveJob;
        private Boolean _playerDataDirty;
        private Boolean _playerSaveRequestedDuringJob;

        private const Double PlayerSaveFrameBudgetMs = 1.5d;
        private const Int32 PlayerSaveItemsPerTick = 12;
        private const Single PlayerSaveDebounceSeconds = 5f;
        private const Single PlayerSaveMinIntervalSeconds = 15f;
        private Single _lastPlayerSaveFinishedAt = -9999f;
        private readonly List<UInt64> _playerSaveKeyBuffer = new List<UInt64>(256);
        private readonly Newtonsoft.Json.JsonSerializer _playerSaveSerializer = Newtonsoft.Json.JsonSerializer.CreateDefault();
        private const String PlayerDataFile = "GiveMenu/PlayerList";
        private const String PlayerTempFile = "GiveMenu/PlayerList.writing";

        private sealed class PlayerSaveJob
        {
            public System.IO.FileStream Stream;
            public System.IO.StreamWriter TextWriter;
            public JsonTextWriter JsonWriter;
            public Newtonsoft.Json.JsonSerializer Serializer;
            public List<UInt64> Keys;
            public Int32 Index;
        }

        private void RequestPlayerSave()
        {
            _playerDataDirty = true;

            if (_playerSaveJob != null)
            {
                _playerSaveRequestedDuringJob = true;
                return;
            }

            if (_playerSaveTimer != null) return;

            Single sinceLast = UnityEngine.Time.realtimeSinceStartup - _lastPlayerSaveFinishedAt;
            Single minDelay = sinceLast >= PlayerSaveMinIntervalSeconds
                ? 0f
                : PlayerSaveMinIntervalSeconds - sinceLast;
            Single delay = Math.Max(PlayerSaveDebounceSeconds, minDelay);

            _playerSaveTimer = timer.Once(delay, () =>
            {
                _playerSaveTimer = null;
                StartIncrementalPlayerSave();
            });
        }

        private void StartIncrementalPlayerSave()
        {
            if (_playerSaveJob != null || !_playerDataDirty || _playerList == null)
                return;

            try
            {
                string tempPath = System.IO.Path.Combine(Interface.Oxide.DataDirectory, PlayerTempFile + ".json");
                string dir = System.IO.Path.GetDirectoryName(tempPath);
                if (!String.IsNullOrEmpty(dir)) System.IO.Directory.CreateDirectory(dir);

                var stream = new System.IO.FileStream(tempPath, System.IO.FileMode.Create,
                    System.IO.FileAccess.Write, System.IO.FileShare.None, 32768,
                    System.IO.FileOptions.SequentialScan);
                var textWriter = new System.IO.StreamWriter(stream, new System.Text.UTF8Encoding(false), 32768);
                var jsonWriter = new JsonTextWriter(textWriter) { Formatting = Formatting.None, CloseOutput = false };
                var serializer = _playerSaveSerializer;
                jsonWriter.WriteStartObject();

                _playerSaveKeyBuffer.Clear();
                foreach (UInt64 userId in _playerList.Keys)
                    _playerSaveKeyBuffer.Add(userId);

                _playerSaveJob = new PlayerSaveJob
                {
                    Stream = stream,
                    TextWriter = textWriter,
                    JsonWriter = jsonWriter,
                    Serializer = serializer,
                    Keys = _playerSaveKeyBuffer,
                    Index = 0
                };

                _playerDataDirty = false;
                _playerSaveRequestedDuringJob = false;
                NextTick(() => ProcessIncrementalPlayerSave(_playerSaveJob));
            }
            catch (Exception ex)
            {
                AbortIncrementalPlayerSave();
                _playerDataDirty = true;
                PrintError("GiveMenu: не удалось начать пошаговое сохранение PlayerList: " + ex.Message);
            }
        }

        private void ProcessIncrementalPlayerSave(PlayerSaveJob job)
        {
            if (job == null || !ReferenceEquals(_playerSaveJob, job)) return;

            try
            {
                Int64 startedTimestamp = System.Diagnostics.Stopwatch.GetTimestamp();
                Int32 processed = 0;

                while (job.Index < job.Keys.Count)
                {
                    UInt64 userId = job.Keys[job.Index++];
                    if (_playerList.TryGetValue(userId, out PlayerData data) && data != null)
                    {
                        job.JsonWriter.WritePropertyName(userId.ToString(CultureInfo.InvariantCulture));
                        job.Serializer.Serialize(job.JsonWriter, data);
                    }

                    processed++;
                    if (processed >= PlayerSaveItemsPerTick ||
                        ElapsedPlayerSaveMs(startedTimestamp) >= PlayerSaveFrameBudgetMs)
                    {
                        NextTick(() => ProcessIncrementalPlayerSave(job));
                        return;
                    }
                }

                FinishIncrementalPlayerSave(job);
            }
            catch (Exception ex)
            {
                AbortIncrementalPlayerSave();
                _playerDataDirty = true;
                PrintError("GiveMenu: пошаговое сохранение PlayerList остановлено: " + ex.Message);
                RequestPlayerSave();
            }
        }

        private static Double ElapsedPlayerSaveMs(Int64 startedTimestamp)
        {
            return (System.Diagnostics.Stopwatch.GetTimestamp() - startedTimestamp) * 1000.0d /
                   System.Diagnostics.Stopwatch.Frequency;
        }

        private void FinishIncrementalPlayerSave(PlayerSaveJob job)
        {
            if (job == null || !ReferenceEquals(_playerSaveJob, job)) return;
            Boolean needAnotherPass = _playerSaveRequestedDuringJob || _playerDataDirty;

            try
            {
                job.JsonWriter.WriteEndObject();
                job.JsonWriter.Flush();
                job.TextWriter.Flush();
                job.Stream.Flush();
                job.JsonWriter.Close();
                job.TextWriter.Dispose();
                job.Stream.Dispose();

                job.JsonWriter = null;
                job.TextWriter = null;
                job.Stream = null;
                _playerSaveJob = null;
                _playerSaveRequestedDuringJob = false;

                string tempPath = System.IO.Path.Combine(Interface.Oxide.DataDirectory, PlayerTempFile + ".json");
                string targetPath = System.IO.Path.Combine(Interface.Oxide.DataDirectory, PlayerDataFile + ".json");
                if (System.IO.File.Exists(targetPath))
                    System.IO.File.Replace(tempPath, targetPath, null);
                else
                    System.IO.File.Move(tempPath, targetPath);

                _lastPlayerSaveFinishedAt = UnityEngine.Time.realtimeSinceStartup;

                if (needAnotherPass)
                {
                    _playerDataDirty = true;
                    RequestPlayerSave();
                }
            }
            catch (Exception ex)
            {
                AbortIncrementalPlayerSave();
                _playerDataDirty = true;
                PrintError("GiveMenu: не удалось завершить сохранение PlayerList: " + ex.Message);
                RequestPlayerSave();
            }
        }

        private void AbortIncrementalPlayerSave()
        {
            PlayerSaveJob job = _playerSaveJob;
            _playerSaveJob = null;
            _playerSaveRequestedDuringJob = false;

            if (job != null)
            {
                try { job.JsonWriter?.Close(); } catch { }
                try { job.TextWriter?.Dispose(); } catch { }
                try { job.Stream?.Dispose(); } catch { }
            }

            try
            {
                string tempPath = System.IO.Path.Combine(Interface.Oxide.DataDirectory, PlayerTempFile + ".json");
                if (System.IO.File.Exists(tempPath)) System.IO.File.Delete(tempPath);
            }
            catch { }
        }

        private void SavePlayerNow()
        {
            _playerSaveTimer?.Destroy();
            _playerSaveTimer = null;
            AbortIncrementalPlayerSave();

            Interface.Oxide.DataFileSystem.WriteObject(PlayerDataFile, _playerList);
            _playerDataDirty = false;
            _lastPlayerSaveFinishedAt = UnityEngine.Time.realtimeSinceStartup;
        }

        private Int32 ResetAllPlayerLimits()
        {
            Int32 affected = _playerList?.Count ?? 0;

            if (_playerList == null)
                _playerList = new Dictionary<UInt64, PlayerData>();
            else
                _playerList.Clear();

            SavePlayerNow();
            return affected;
        }

        private void LoadPlayer()
        {
            try
            {
                _playerList =
                    Interface.Oxide.DataFileSystem.ReadObject<Dictionary<UInt64, PlayerData>>(
                        $"{Name}/PlayerList");
            }
            catch (Exception e)
            {
                PrintError(e.ToString());
            }

            if (_playerList is null)
                _playerList = new Dictionary<UInt64, PlayerData>();

            foreach (UInt64 userId in _playerList.Keys.ToList())
                GetPlayerData(userId);
        }

        #endregion

        #region [Wipe-Cooldown]

        private void LoadWipeState()
        {
            try
            {
                _wipeState = Interface.Oxide.DataFileSystem.ReadObject<WipeState>(WipeDataFile) ?? new WipeState();
            }
            catch
            {
                _wipeState = new WipeState();
            }
        }

        private void SaveWipeState()
        {
            Interface.Oxide.DataFileSystem.WriteObject(WipeDataFile, _wipeState ?? new WipeState());
        }

        private Int64 CurrentSaveCreatedUnix()
        {
            try
            {
                return Facepunch.Math.Epoch.FromDateTime(SaveRestore.SaveCreatedTime);
            }
            catch
            {
                return 0L;
            }
        }

        private void EnsureCurrentWipeCooldown()
        {
            if (_wipeState == null)
                _wipeState = new WipeState();

            Int64 currentSave = CurrentSaveCreatedUnix();

                                                                          
                                                              
            if (_wipeState.SaveCreatedUnix <= 0L)
            {
                _wipeState.SaveCreatedUnix = currentSave;

                if (!_wipeState.Initialized && String.IsNullOrEmpty(_wipeState.SaveName) && _wipeState.UnlockAt <= 0d)
                {
                    _wipeState.UnlockAt = UnixNow() + Math.Max(0d, _config?.WipeCooldownHours ?? 2d) * 3600d;
                    PrintWarning("GiveMenu: состояние вайпа создаётся впервые. КД меню выдачи запущен сейчас.");
                }

                _wipeState.Initialized = true;
                SaveWipeState();
                return;
            }

            if (currentSave > 0L && _wipeState.SaveCreatedUnix != currentSave)
                ResetForNewWipe(String.Empty, currentSave);
        }

        private void OnNewSave(String filename)
        {
            if (_wipeState == null)
                _wipeState = new WipeState();

            Int64 currentSave = CurrentSaveCreatedUnix();
            String saveName = filename ?? String.Empty;

            Boolean sameEpoch = currentSave > 0L && _wipeState.SaveCreatedUnix == currentSave;
            Boolean sameName = !String.IsNullOrEmpty(saveName) &&
                               String.Equals(_wipeState.SaveName, saveName, StringComparison.Ordinal);
            if (sameEpoch && sameName)
                return;

                                                                                                
                                                                                                          
            Int64 epochToStore = sameEpoch && !sameName ? 0L : currentSave;
            ResetForNewWipe(saveName, epochToStore);
        }

        private void ResetForNewWipe(String saveName, Int64 saveCreatedUnix)
        {
            if (_wipeState == null)
                _wipeState = new WipeState();

            if (!String.IsNullOrEmpty(saveName))
                _wipeState.SaveName = saveName;
            _wipeState.SaveCreatedUnix = Math.Max(0L, saveCreatedUnix);
            _wipeState.UnlockAt = UnixNow() + Math.Max(0d, _config?.WipeCooldownHours ?? 2d) * 3600d;
            _wipeState.Initialized = true;
            SaveWipeState();

            Int32 resetPlayers = ResetAllPlayerLimits();
            Puts("GiveMenu: обнаружен новый вайп. Лимиты выдачи сброшены для " +
                 resetPlayers.ToString(CultureInfo.InvariantCulture) + " игроков.");

            foreach (BasePlayer player in BasePlayer.activePlayerList)
            {
                if (player == null || !player.IsConnected || HasAdmin(player))
                    continue;

                CuiHelper.DestroyUi(player, MainPanel);
                CuiHelper.DestroyUi(player, Layer);
            }
        }

        private Boolean IsWipeCooldownActive(BasePlayer player)
        {
            if (player != null && HasAdmin(player))
                return false;

            return _wipeState != null && _wipeState.UnlockAt > UnixNow();
        }

        private Double WipeCooldownLeft()
        {
            return _wipeState == null ? 0d : Math.Max(0d, _wipeState.UnlockAt - UnixNow());
        }

        private String WipeCooldownRemaining()
        {
            Double seconds = WipeCooldownLeft();
            Int32 totalMinutes = Math.Max(1, (Int32)Math.Ceiling(seconds / 60d));
            Int32 hours = totalMinutes / 60;
            Int32 minutes = totalMinutes % 60;

            if (hours > 0 && minutes > 0)
                return hours + " ч " + minutes + " мин";
            if (hours > 0)
                return hours + " ч";
            return minutes + " мин";
        }

        private String WipeCooldownMessage()
        {
            return "Меню выдачи станет доступно через " + WipeCooldownRemaining() + " после вайпа.";
        }

        private static Double UnixNow()
        {
            return DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        }

        private String GetSiteQrPng()
        {
            if (!String.IsNullOrEmpty(_siteQrPng))
                return _siteQrPng;

            CommunityEntity entity = CommunityEntity.ServerInstance;
            if (entity == null || entity.net == null || FileStorage.server == null)
                return null;

            _siteQrPng = FileStorage.server.Store(
                Convert.FromBase64String(SiteQrPngBase64),
                FileStorage.Type.png, entity.net.ID).ToString(CultureInfo.InvariantCulture);
            return _siteQrPng;
        }

        #endregion

        #region [Oxide-Api]

        private void OnServerInitialized()
        {
            LoadPlayer();
            LoadPersonalBlacklist();
            LoadWipeState();
            EnsureCurrentWipeCooldown();

            permission.RegisterPermission(Permission, this);
            permission.RegisterPermission(AdminPermission, this);
            permission.RegisterPermission(UnlimitedPermission, this);

            m_ItemDefinitionsPerCategory.Clear();

            foreach (ItemDefinition itemDefinition in ItemManager.itemList)
            {
                if (itemDefinition == null)
                    continue;

                if (!m_ItemDefinitionsPerCategory.TryGetValue(
                        itemDefinition.category,
                        out List<ItemDefinition> itemDefinitions))
                {
                    itemDefinitions = new List<ItemDefinition>();
                    m_ItemDefinitionsPerCategory[itemDefinition.category] = itemDefinitions;
                }

                itemDefinitions.Add(itemDefinition);
            }

            foreach (KeyValuePair<ItemCategory, List<ItemDefinition>> kvp in m_ItemDefinitionsPerCategory)
            {
                kvp.Value.Sort((a, b) =>
                    String.Compare(
                        a?.displayName?.english ?? a?.shortname ?? String.Empty,
                        b?.displayName?.english ?? b?.shortname ?? String.Empty,
                        StringComparison.OrdinalIgnoreCase));
            }

            _categories = m_ItemDefinitionsPerCategory
                .Where(x => x.Value != null && x.Value.Count > 0)
                .OrderBy(x => CategorySortOrder(x.Key))
                .ThenBy(x => x.Key.ToString(), StringComparer.OrdinalIgnoreCase)
                .ToList();

            Boolean configChanged = false;
            foreach (KeyValuePair<ItemCategory, List<ItemDefinition>> category in _categories)
            {
                String key = category.Key.ToString();
                if (_config.CategoryLimits.ContainsKey(key))
                    continue;

                _config.CategoryLimits[key] = Math.Max(0, _config.DefaultCategoryLimit);
                configChanged = true;
            }

            if (configChanged)
                SaveConfig();

            RegisterTab();
        }

        private void OnPluginLoaded(Plugin plugin)
        {
            if (plugin != null && plugin.Name == "ServerMenu") RegisterTab();
        }

        private void OnPlayerDisconnected(BasePlayer player, string reason)
        {
            if (player == null) return;

            _menuStates.Remove(player.userID);
            _adminMenuStates.Remove(player.userID);
            _hostAdmin.Remove(player.userID);

            CuiHelper.DestroyUi(player, MainPanel);
            CuiHelper.DestroyUi(player, AdminMainPanel);
            CuiHelper.DestroyUi(player, Layer);
            CuiHelper.DestroyUi(player, AdminLayer);
        }

        private Plugin Menu()
        {
            if (ServerMenu != null && ServerMenu.IsLoaded) return ServerMenu;
            ServerMenu = Interface.Oxide.RootPluginManager.GetPlugin("ServerMenu");
            return ServerMenu != null && ServerMenu.IsLoaded ? ServerMenu : null;
        }

        private void RegisterTab()
        {
            Plugin menu = Menu();
            if (menu == null) return;

            menu.Call("API_RegisterTab", this, HostTab, HostTitle, HostIcon, HostOrder);
        }

        private Boolean HasAccess(BasePlayer player)
        {
            return player != null && permission.UserHasPermission(player.UserIDString, Permission);
        }

        private Single HostHeight()
        {
            Plugin menu = Menu();
            if (menu == null) return HostHeightFallback;

            object raw = menu.Call("API_ContentHeight");
            return raw is Single height && height > 0f ? height : HostHeightFallback;
        }

        private Single BottomOf(MenuState state)
        {
            return state != null && state.Hosted
                ? -HostHeight() + HostBottomPad
                : ContentBottomY;
        }

        private void Unload()
        {
            Plugin menu = Menu();
            if (menu != null) menu.Call("API_UnregisterTab", HostTab);

            SavePlayerNow();
            SaveWipeState();

            foreach (BasePlayer player in BasePlayer.activePlayerList)
            {
                CuiHelper.DestroyUi(player, MainPanel);
                CuiHelper.DestroyUi(player, AdminMainPanel);
                CuiHelper.DestroyUi(player, Layer);
                CuiHelper.DestroyUi(player, AdminLayer);
            }

            _menuStates.Clear();
            _adminMenuStates.Clear();
            _hostAdmin.Clear();
            _categories?.Clear();

            if (_playerList is not null)
            {
                _playerList.Clear();
                _playerList = null;
            }

            if (m_ItemDefinitionsPerCategory is not null)
            {
                m_ItemDefinitionsPerCategory.Clear();
                m_ItemDefinitionsPerCategory = null;
            }
        }

        #endregion

        #region [Interface]

        private const Single MainTopY = UiStyle.TabsTop;
        private const Single MainBottomY = UiStyle.MainBottom;

        private const Single NavLeft = 22f;
        private const Single NavRight = 242f;
        private const Single NavRowHeight = 26f;
        private const Single GridLeft = 254f;
        private const Single GridRight = 714f;
        private const Single CardLeft = 726f;
        private const Single CardRight = 1098f;
        private const Single ContentBottomY = -608f;

        private const Single SearchTop = UiStyle.ContentTop;
        private const Single SearchHeight = 32f;
        private const Single GridTop = SearchTop - SearchHeight - 8f;

        private const Int32 GridColumns = 6;
        private const Int32 GridItemsPerPage = 36;
        private const Single GridPagerHeight = 34f;
        private const Single SlotSize = 68f;
        private const Single SlotGap = 8f;
        private const Single GridPad = 6f;

        private const Single CardIcon = 96f;
        private const Single AdminFooterHeight = 48f;

        private const Single LimitGap = 12f;
        private const Single RowLimitInput = 10f;
        private const Single RowLimitLabel = 40f;
        private const Single RowBlacklist = 60f;
        private const Single AdminStackH = 88f;
        private const Single RowGive = 8f;
        private const Single RowQty = 48f;
        private const Single RowIssued = 88f;
        private const Single RowRule = 118f;
        private const Single RowStack = 124f;
        private const Single StackTotal = 154f;
        private const Single DescTop = -184f;
        private const Single DescMin = 44f;
        private const Int32 SearchLimit = 24;
        private const Int32 LimitLimit = 10;

        private String RootOf(Boolean admin) => admin ? AdminLayer : Layer;

        private String MainOf(Boolean admin) => admin ? AdminMainPanel : MainPanel;

        private String CommandOf(Boolean admin) => admin ? "UI_GIVE_ADMIN " : "UI_GIVE ";

        private MenuState StateOf(BasePlayer player, Boolean admin) =>
            admin ? GetAdminMenuState(player) : GetMenuState(player);

        private String NavPanel(Boolean admin) => MainOf(admin) + ".Nav";

        private String HeadPanel(Boolean admin) => MainOf(admin) + ".Head";

        private String GridPanel(Boolean admin) => MainOf(admin) + ".Grid";

        private String CardPanel(Boolean admin) => MainOf(admin) + ".Card";

        private String QtyPanel(Boolean admin) => CardPanel(admin) + ".Qty";

        private String GivePanel(Boolean admin) => CardPanel(admin) + ".Give";

        private static String ItemTitle(ItemDefinition definition)
        {
            if (definition == null)
                return String.Empty;

            String title = definition.displayName?.english;

            if (String.IsNullOrWhiteSpace(title) || title.StartsWith("#", StringComparison.Ordinal))
                return definition.shortname;

            return title;
        }

        private static String ItemDescription(ItemDefinition definition)
        {
            if (definition == null)
                return String.Empty;

            String text = definition.displayDescription?.english;

            if (String.IsNullOrWhiteSpace(text) || text.StartsWith("#", StringComparison.Ordinal))
                return String.Empty;

            return text;
        }

        private Boolean IsUnlimited(BasePlayer player)
        {
            return player != null &&
                   permission.UserHasPermission(player.UserIDString, UnlimitedPermission);
        }

        private Int32 MaxGive(ItemDefinition definition, BasePlayer player)
        {
            if (definition == null)
                return 0;

            Int32 stack = Math.Max(1, definition.stackable);

            if (IsUnlimited(player))
                return stack;

            Int32 categoryLimit = GetCategoryLimit(definition.category);
            Int32 itemLimit = GetItemLimit(definition.shortname);

            if (categoryLimit <= 0 && itemLimit <= 0)
                return stack;

            PlayerData data = GetPlayerData(player.userID);
            Int32 allowed = stack;

            if (categoryLimit > 0)
                allowed = Math.Min(allowed,
                    Math.Max(0, categoryLimit - GetCategoryAmount(data, definition.category)));

            if (itemLimit > 0)
                allowed = Math.Min(allowed,
                    Math.Max(0, itemLimit - GetItemAmount(data, definition.shortname)));

            return Math.Max(0, allowed);
        }

        private List<ItemDefinition> Allowed(IEnumerable<ItemDefinition> items, Boolean admin, UInt64 viewer)
        {
            return (items ?? new List<ItemDefinition>())
                .Where(x => x != null && (admin || !IsBlockedFor(viewer, x.shortname)))
                .ToList();
        }

        private Int32 AllowedCount(List<ItemDefinition> items, Boolean admin, UInt64 viewer)
        {
            if (items == null) return 0;
            Int32 count = 0;
            for (Int32 i = 0; i < items.Count; i++)
            {
                ItemDefinition item = items[i];
                if (item != null && (admin || !IsBlockedFor(viewer, item.shortname))) count++;
            }
            return count;
        }

        private List<ItemDefinition> VisibleItems(MenuState state, Boolean admin, out Boolean searchedAll)
        {
            searchedAll = false;

            List<KeyValuePair<ItemCategory, List<ItemDefinition>>> categories = GetCategories();
            if (categories.Count == 0)
                return new List<ItemDefinition>();

            Int32 index = Mathf.Clamp(state.Category, 0, categories.Count - 1);
            String query = state.Search?.Trim();

                                                                               
                                                                                       
            List<ItemDefinition> source = admin && String.IsNullOrEmpty(query)
                ? (categories[index].Value ?? new List<ItemDefinition>())
                : Allowed(categories[index].Value, admin, state.UserId);

            if (String.IsNullOrEmpty(query))
                return source;

            List<ItemDefinition> found = source.Where(x => Matches(x, query)).ToList();
            if (found.Count > 0)
                return found;

            searchedAll = true;
            return Allowed(categories.SelectMany(x => x.Value ?? new List<ItemDefinition>()), admin, state.UserId)
                .Where(x => Matches(x, query))
                .OrderBy(x => ItemTitle(x), StringComparer.OrdinalIgnoreCase)
                .ToList();
        }

        private static Boolean Matches(ItemDefinition definition, String query)
        {
            if (definition == null)
                return false;

            return ItemTitle(definition).IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0 ||
                   (definition.shortname ?? String.Empty).IndexOf(query, StringComparison.OrdinalIgnoreCase) >= 0;
        }

        private ItemDefinition Selected(MenuState state)
        {
            if (state == null || String.IsNullOrEmpty(state.Item))
                return null;

            return ItemManager.FindItemDefinition(state.Item);
        }

        private String API_TabTitle(BasePlayer player)
        {
            return HasAccess(player) && !IsWipeCooldownActive(player) ? HostTitle : HostLockedTitle;
        }

        private void API_PrepareServerMenu(BasePlayer player, String mode)
        {
            if (player == null) return;

            _hostAdmin.Remove(player.userID);

            MenuState state = GetMenuState(player);
            state.Hosted = true;
            state.Category = 0;
            state.Item = String.Empty;
            state.Amount = 1;
            state.Search = String.Empty;
            state.GridPage = 0;
        }

        private void API_RenderServerMenu(BasePlayer player)
        {
            if (player == null) return;

            Boolean admin = _hostAdmin.Contains(player.userID) && HasAdmin(player);
            if (!admin) _hostAdmin.Remove(player.userID);

            StateOf(player, admin).Hosted = true;

            CuiHelper.DestroyUi(player, MainOf(!admin));

            CuiElementContainer container = new CuiElementContainer();

            container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" },
                Image = { Color = UiStyle.ColClear, BlocksRaycast = false }
            }, HostMain, MainOf(admin), MainOf(admin));

            DrawContent(container, player, admin);
            CuiHelper.AddUi(player, container);
        }

        private void API_OnTabHidden(BasePlayer player)
        {
            if (player == null) return;

            CuiHelper.DestroyUi(player, MainPanel);
            CuiHelper.DestroyUi(player, AdminMainPanel);

            _hostAdmin.Remove(player.userID);

            MenuState state = GetMenuState(player);
            state.Hosted = false;
            state.Item = String.Empty;
            state.Search = String.Empty;
            state.GridPage = 0;

            MenuState admin = GetAdminMenuState(player);
            admin.Hosted = false;
            admin.Item = String.Empty;
            admin.Search = String.Empty;
            admin.GridPage = 0;
        }

        private void API_OnTabCommand(BasePlayer player, String payload)
        {
            if (player == null || String.IsNullOrEmpty(payload)) return;
            if (payload == "admin") ToggleAdminMode(player);
        }

        private void ToggleAdminMode(BasePlayer player)
        {
            if (!HasAdmin(player)) return;

            Boolean hosted = GetMenuState(player).Hosted || GetAdminMenuState(player).Hosted;

            if (!hosted)
            {
                MenuState standalone = GetAdminMenuState(player);
                standalone.Hosted = false;
                standalone.Category = 0;
                standalone.Item = String.Empty;
                standalone.Amount = 1;
                standalone.Search = String.Empty;
                standalone.GridPage = 0;

                DrawMenu(player, true);
                return;
            }

            Boolean next = !_hostAdmin.Contains(player.userID);

            if (next) _hostAdmin.Add(player.userID);
            else _hostAdmin.Remove(player.userID);

            MenuState state = StateOf(player, next);
            state.Hosted = true;
            state.Category = 0;
            state.Item = String.Empty;
            state.Amount = 1;
            state.Search = String.Empty;
            state.GridPage = 0;

            API_RenderServerMenu(player);
        }

        private void DrawMenu(BasePlayer player, Boolean admin)
        {
            String root = RootOf(admin);
            String main = MainOf(admin);

            CuiHelper.DestroyUi(player, root);

            CuiElementContainer container = new CuiElementContainer();

            UiStyle.Window(container, "Overlay", root);

            container.Add(new CuiButton
            {
                Button = { Color = UiStyle.ColClear, Close = root },
                Text = { Text = String.Empty },
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" }
            }, root, root + ".CloseArea");

            UiStyle.Header(container, root, main + ".Header",
                admin ? "ВЫДАЧА · НАСТРОЙКА" : "ВЫДАЧА",
                player.displayName +
                "   <color=" + UiStyle.ColMuted + ">•</color>   " +
                BasePlayer.activePlayerList.Count.ToString(CultureInfo.InvariantCulture) + " ONLINE" +
                (IsUnlimited(player)
                    ? "   <color=" + UiStyle.ColMuted + ">•</color>   <color=" +
                      UiStyle.ColGreenBtn + ">БЕЗ ЛИМИТОВ</color>"
                    : String.Empty),
                String.Empty, root);

            UiStyle.Body(container, root, main, MainTopY, MainBottomY);

            DrawContent(container, player, admin);

            CuiHelper.AddUi(player, container);
        }

        private void DrawContent(CuiElementContainer container, BasePlayer player, Boolean admin)
        {
            MenuState state = StateOf(player, admin);
            state.Category = Mathf.Clamp(state.Category, 0, Math.Max(0, GetCategories().Count - 1));
            state.Amount = Math.Max(1, state.Amount);

            if (!admin && !HasAccess(player))
            {
                UiStyle.LockedPanel(container, MainOf(admin), MainOf(admin) + ".Locked",
                    _config.NoAccessTitle, _config.NoAccessText,
                    _config.Contacts == null ? null : _config.Contacts.ToArray(),
                    GetSiteQrPng(), SiteUrl);
                return;
            }

            if (!admin && IsWipeCooldownActive(player))
            {
                UiStyle.LockedPanel(container, MainOf(admin), MainOf(admin) + ".WipeLocked",
                    "ВЫДАЧА ВРЕМЕННО ЗАКРЫТА",
                    "После вайпа действует задержка 2 часа. До открытия: " + WipeCooldownRemaining() + ".",
                    null);
                return;
            }

            DrawNav(container, state, admin);
            DrawHead(container, player, state, admin);
            DrawGrid(container, state, admin);
            DrawCard(container, player, state, admin);

        }

        private Boolean HasAdmin(BasePlayer player)
        {
            return player != null && permission.UserHasPermission(player.UserIDString, AdminPermission);
        }

        private void DrawAdminSwitch(CuiElementContainer container, String parent, Boolean admin)
        {
            UiStyle.Secondary(container, parent, "1 1", "1 1",
                UiStyle.Off(-AdminBtnW - UiStyle.PadX, -AdminBtnTop - UiStyle.BtnH),
                UiStyle.Off(-UiStyle.PadX, -AdminBtnTop),
                "UI_GIVE adminmode",
                admin ? "ОБЫЧНЫЙ РЕЖИМ" : "РЕЖИМ АДМИНИСТРАТОРА", true);
        }

        private void DrawNav(CuiElementContainer container, MenuState state, Boolean admin)
        {
            String main = MainOf(admin);
            String nav = NavPanel(admin);

            UiStyle.Panel(container, main, nav, "0 1", "0 1",
                UiStyle.Off(NavLeft, BottomOf(state)), UiStyle.Off(NavRight, UiStyle.ContentTop), UiStyle.ColClear);

            List<KeyValuePair<ItemCategory, List<ItemDefinition>>> categories = GetCategories();
            Single width = NavRight - NavLeft;

            for (Int32 i = 0; i < categories.Count; i++)
            {
                Int32 count = AllowedCount(categories[i].Value, admin, state.UserId);
                Single top = -i * NavRowHeight;

                UiStyle.NavItem(container, nav, nav + "." + i.ToString(CultureInfo.InvariantCulture),
                    0f, width, top, NavRowHeight,
                    UiStyle.CategoryIcon(categories[i].Key.ToString()), CategoryDisplayName(categories[i].Key),
                    count.ToString(CultureInfo.InvariantCulture),
                    state.Category == i, CommandOf(admin) + "switch " + i.ToString(CultureInfo.InvariantCulture));
            }

            if (!admin)
                return;

            String footer = nav + ".Default";
            Single navHeight = UiStyle.ContentTop - BottomOf(state);
            Single footerTop = -(navHeight - AdminFooterHeight);

            UiStyle.Card(container, nav, footer, 0f, width, footerTop, footerTop - AdminFooterHeight,
                UiStyle.ColCardDark, UiStyle.ColSecondaryBtn);

            UiStyle.Label(container, footer, "0 1", "1 1",
                UiStyle.Off(UiStyle.PadRow, -24f), UiStyle.Off(-UiStyle.PadRow, -6f),
                "ЛИМИТ ПО УМОЛЧАНИЮ", UiStyle.SizeTiny, UiStyle.ColMuted, TextAnchor.MiddleLeft, UiStyle.Bold);

            UiStyle.Label(container, footer, "0 0", "1 0",
                UiStyle.Off(UiStyle.PadRow, 8f), UiStyle.Off(-UiStyle.PadRow, 28f),
                _config.DefaultCategoryLimit <= 0
                    ? "БЕЗ ЛИМИТА"
                    : _config.DefaultCategoryLimit.ToString("N0", CultureInfo.InvariantCulture),
                UiStyle.SizeCardTitle, UiStyle.ColText, TextAnchor.MiddleLeft, UiStyle.Bold);
        }

        private void DrawHead(CuiElementContainer container, BasePlayer player, MenuState state, Boolean admin)
        {
            String main = MainOf(admin);
            String head = HeadPanel(admin);

            UiStyle.Panel(container, main, head, "0 1", "1 1",
                UiStyle.Off(0f, UiStyle.HeadingLine - 1f), UiStyle.Off(0f, 0f), UiStyle.ColClear);

            if (state.Hosted && HasAdmin(player))
                DrawAdminSwitch(container, head, admin);

            List<KeyValuePair<ItemCategory, List<ItemDefinition>>> categories = GetCategories();
            if (categories.Count == 0)
                return;

            Int32 index = Mathf.Clamp(state.Category, 0, categories.Count - 1);
            ItemCategory category = categories[index].Key;

            Boolean searchedAll;
            List<ItemDefinition> items = VisibleItems(state, admin, out searchedAll);

            PlayerData data = GetPlayerData(player.userID);
            Int32 used = GetCategoryAmount(data, category);
            Int32 limit = GetCategoryLimit(category);

            String subtitle =
                items.Count.ToString(CultureInfo.InvariantCulture) + " ПРЕДМЕТОВ" +
                (searchedAll ? "   ·   ПОИСК ПО ВСЕМ КАТЕГОРИЯМ" : String.Empty) +
                "   ·   ВЫДАНО " + used.ToString("N0", CultureInfo.InvariantCulture) + " / " +
                (limit <= 0 || IsUnlimited(player)
                    ? "БЕЗ ЛИМИТА"
                    : limit.ToString("N0", CultureInfo.InvariantCulture));

            UiStyle.Heading(container, head, CategoryDisplayName(category), subtitle);
        }

        private void DrawGrid(CuiElementContainer container, MenuState state, Boolean admin)
        {
            String main = MainOf(admin);
            String grid = GridPanel(admin);

            UiStyle.Panel(container, main, grid, "0 1", "0 1",
                UiStyle.Off(GridLeft, BottomOf(state)), UiStyle.Off(GridRight, SearchTop), UiStyle.ColClear);

            Single width = GridRight - GridLeft;

            UiStyle.Input(container, grid, grid + ".Search", 0f, width, 0f, -SearchHeight,
                state.Search ?? String.Empty, "ПОИСК ПО НАЗВАНИЮ",
                CommandOf(admin) + "find ", SearchLimit);

            Boolean searchedAll;
            List<ItemDefinition> items = VisibleItems(state, admin, out searchedAll);

            Int32 pageCount = Math.Max(1, (items.Count + GridItemsPerPage - 1) / GridItemsPerPage);
            state.GridPage = Mathf.Clamp(state.GridPage, 0, pageCount - 1);
            Int32 start = state.GridPage * GridItemsPerPage;
            Int32 end = Math.Min(items.Count, start + GridItemsPerPage);
            Int32 visibleCount = Math.Max(0, end - start);

            Int32 rows = Mathf.Max(1, (visibleCount + GridColumns - 1) / GridColumns);
            Single pagerExtra = pageCount > 1 ? GridPagerHeight + 8f : 0f;
            Single contentHeight = Mathf.Max(
                GridTop - BottomOf(state),
                GridPad * 2f + rows * SlotSize + Math.Max(0, rows - 1) * SlotGap + pagerExtra);

            String scroll = grid + ".Scroll";

            container.Add(new CuiElement
            {
                Name = scroll,
                Parent = grid,
                Components =
                {
                    new CuiImageComponent { Color = UiStyle.ColCardDark },
                    new CuiScrollViewComponent
                    {
                        MovementType = ScrollRect.MovementType.Clamped,
                        Vertical = true,
                        Horizontal = false,
                        Inertia = true,
                        Elasticity = 0.20f,
                        DecelerationRate = 0.25f,
                        ScrollSensitivity = 24f,
                        ContentTransform = new CuiRectTransform
                        {
                            AnchorMin = "0 1",
                            AnchorMax = "1 1",
                            OffsetMin = "0 -" + F(contentHeight),
                            OffsetMax = "0 0"
                        },
                        VerticalScrollbar = new CuiScrollbar
                        {
                            Size = -1,
                            AutoHide = true
                        }
                    },
                    new CuiRectTransformComponent
                    {
                        AnchorMin = "0 0",
                        AnchorMax = "1 1",
                        OffsetMin = "0 0",
                        OffsetMax = "0 " + F(GridTop - SearchTop)
                    }
                }
            });

            Int32 localIndex = 0;
            for (Int32 i = start; i < end; i++, localIndex++)
                DrawSlot(container, state, admin, items[i], localIndex);

            if (pageCount > 1)
            {
                Single pagerTop = -GridPad - rows * SlotSize - Math.Max(0, rows - 1) * SlotGap - 8f;
                Single pagerBottom = pagerTop - GridPagerHeight;
                Single center = width * 0.5f;

                UiStyle.Secondary(container, scroll, "0 1", "0 1",
                    UiStyle.Off(center - 112f, pagerBottom), UiStyle.Off(center - 48f, pagerTop),
                    CommandOf(admin) + "page " + (state.GridPage - 1).ToString(CultureInfo.InvariantCulture),
                    "<", state.GridPage > 0);

                UiStyle.Label(container, scroll, "0 1", "0 1",
                    UiStyle.Off(center - 44f, pagerBottom), UiStyle.Off(center + 44f, pagerTop),
                    (state.GridPage + 1).ToString(CultureInfo.InvariantCulture) + "/" + pageCount.ToString(CultureInfo.InvariantCulture),
                    UiStyle.SizeSmall, UiStyle.ColText, TextAnchor.MiddleCenter, UiStyle.Bold);

                UiStyle.Secondary(container, scroll, "0 1", "0 1",
                    UiStyle.Off(center + 48f, pagerBottom), UiStyle.Off(center + 112f, pagerTop),
                    CommandOf(admin) + "page " + (state.GridPage + 1).ToString(CultureInfo.InvariantCulture),
                    ">", state.GridPage < pageCount - 1);
            }
        }

        private void DrawSlot(CuiElementContainer container, MenuState state, Boolean admin,
            ItemDefinition definition, Int32 index)
        {
            if (definition == null)
                return;

            String scroll = GridPanel(admin) + ".Scroll";

            Int32 column = index % GridColumns;
            Int32 row = index / GridColumns;
            Single left = GridPad + column * (SlotSize + SlotGap);
            Single top = -GridPad - row * (SlotSize + SlotGap);

            Boolean blocked = admin && IsBlacklisted(definition.shortname);

            UiStyle.Slot(container, scroll, scroll + ".Item." + index.ToString(CultureInfo.InvariantCulture),
                left, left + SlotSize, top, top - SlotSize,
                definition.itemid, 0UL, null,
                String.Equals(state.Item, definition.shortname, StringComparison.OrdinalIgnoreCase),
                blocked, blocked,
                CommandOf(admin) + "pick " + definition.shortname);
        }

        private void RedrawSlot(CuiElementContainer container, MenuState state, Boolean admin, String shortName)
        {
            if (String.IsNullOrEmpty(shortName))
                return;

            Boolean searchedAll;
            List<ItemDefinition> items = VisibleItems(state, admin, out searchedAll);

            Int32 absoluteIndex = items.FindIndex(x =>
                x != null && String.Equals(x.shortname, shortName, StringComparison.OrdinalIgnoreCase));

            if (absoluteIndex < 0) return;

            Int32 start = Math.Max(0, state.GridPage) * GridItemsPerPage;
            Int32 end = Math.Min(items.Count, start + GridItemsPerPage);
            if (absoluteIndex < start || absoluteIndex >= end) return;

            DrawSlot(container, state, admin, items[absoluteIndex], absoluteIndex - start);
        }

        private void DrawCard(CuiElementContainer container, BasePlayer player, MenuState state, Boolean admin)
        {
            String main = MainOf(admin);
            String card = CardPanel(admin);

            ItemDefinition definition = Selected(state);

            UiStyle.Card(container, main, card, CardLeft, CardRight, UiStyle.ContentTop, BottomOf(state),
                UiStyle.ColCard, definition == null ? UiStyle.ColMuted : UiStyle.ColGreenBtn);

            if (definition == null)
            {
                UiStyle.Label(container, card, "0 0", "1 1", "0 0", "0 0",
                    "ВЫБЕРИТЕ ПРЕДМЕТ", UiStyle.SizeBody, UiStyle.ColMuted, TextAnchor.MiddleCenter, UiStyle.Reg);
                return;
            }

            Single width = CardRight - CardLeft;
            Single pad = UiStyle.PadCardWide;

            UiStyle.Panel(container, card, card + ".Icon", "0.5 1", "0.5 1",
                UiStyle.Off(-CardIcon / 2f, -14f - CardIcon), UiStyle.Off(CardIcon / 2f, -14f), UiStyle.ColCardDark);

            UiStyle.ItemIcon(container, card + ".Icon", "0 0", "1 1", "8 8", "-8 -8",
                definition.itemid, 0UL, UiStyle.ColTint);

            UiStyle.Label(container, card, "0 1", "1 1", UiStyle.Off(pad, -144f), UiStyle.Off(-pad, -118f),
                ShortText(ItemTitle(definition), 28), UiStyle.SizeSectionValue, UiStyle.ColText,
                TextAnchor.MiddleCenter, UiStyle.Bold);

            UiStyle.Label(container, card, "0 1", "1 1", UiStyle.Off(pad, -164f), UiStyle.Off(-pad, -146f),
                definition.shortname, UiStyle.SizeSmall, UiStyle.ColMuted, TextAnchor.MiddleCenter, UiStyle.Reg);

            UiStyle.Panel(container, card, card + ".L1", "0 1", "1 1",
                UiStyle.Off(pad, -175f), UiStyle.Off(-pad, -174f), UiStyle.ColSep);

            PlayerData data = GetPlayerData(player.userID);
            ItemCategory category = definition.category;
            Int32 used = GetCategoryAmount(data, category);
            Int32 limit = GetCategoryLimit(category);
            Int32 stack = Math.Max(1, definition.stackable);
            Boolean unlimited = IsUnlimited(player);

            Single cardHeight = UiStyle.ContentTop - BottomOf(state);
            Single adminStack = admin ? AdminStackH : 0f;
            Single descBottom = -(cardHeight - adminStack - StackTotal);

            if (DescTop - descBottom >= DescMin)
                UiStyle.Label(container, card, "0 1", "1 1", UiStyle.Off(pad, descBottom), UiStyle.Off(-pad, DescTop),
                    ItemDescription(definition), UiStyle.SizeBody, UiStyle.ColSubText, TextAnchor.UpperLeft, UiStyle.Reg);

            UiStyle.Label(container, card, "0 0", "1 0",
                UiStyle.Off(pad, adminStack + RowStack), UiStyle.Off(-pad, adminStack + RowStack + 22f),
                "МАКСИМАЛЬНЫЙ СТАК", UiStyle.SizeSmall, UiStyle.ColMuted, TextAnchor.MiddleLeft, UiStyle.Bold);

            UiStyle.Label(container, card, "0 0", "1 0",
                UiStyle.Off(pad, adminStack + RowStack), UiStyle.Off(-pad, adminStack + RowStack + 22f),
                stack.ToString("N0", CultureInfo.InvariantCulture), UiStyle.SizeCardTitle, UiStyle.ColText,
                TextAnchor.MiddleRight, UiStyle.Bold);

            UiStyle.Panel(container, card, card + ".L2", "0 0", "1 0",
                UiStyle.Off(pad, adminStack + RowRule), UiStyle.Off(-pad, adminStack + RowRule + 1f), UiStyle.ColSep);

            Int32 itemLimit = GetItemLimit(definition.shortname);
            Int32 itemUsed = GetItemAmount(data, definition.shortname);

            Boolean byItem = itemLimit > 0 &&
                             (limit <= 0 || itemLimit - itemUsed < limit - used);

            Int32 issuedUsed = byItem ? itemUsed : used;
            Int32 issuedLimit = byItem ? itemLimit : limit;

            UiStyle.Label(container, card, "0 0", "1 0",
                UiStyle.Off(pad, adminStack + RowIssued), UiStyle.Off(-pad, adminStack + RowIssued + 22f),
                byItem ? "ВЫДАНО ПРЕДМЕТА" : "ВЫДАНО В КАТЕГОРИИ",
                UiStyle.SizeSmall, UiStyle.ColMuted, TextAnchor.MiddleLeft, UiStyle.Bold);

            UiStyle.Label(container, card, "0 0", "1 0",
                UiStyle.Off(pad, adminStack + RowIssued), UiStyle.Off(-pad, adminStack + RowIssued + 22f),
                issuedUsed.ToString("N0", CultureInfo.InvariantCulture) + " / " +
                (issuedLimit <= 0 || unlimited ? "∞" : issuedLimit.ToString("N0", CultureInfo.InvariantCulture)),
                UiStyle.SizeCardTitle,
                !unlimited && issuedLimit > 0 && issuedUsed >= issuedLimit ? UiStyle.ColRedText : UiStyle.ColText,
                TextAnchor.MiddleRight, UiStyle.Bold);

            DrawQty(container, player, state, admin);
            DrawGive(container, player, state, admin);

            if (!admin)
                return;

            Boolean blocked = IsBlacklisted(definition.shortname);

            UiStyle.Label(container, card, "0 0", "1 0",
                UiStyle.Off(pad, RowBlacklist), UiStyle.Off(-pad, RowBlacklist + 28f),
                "В ЧЁРНОМ СПИСКЕ", UiStyle.SizeSmall, UiStyle.ColMuted, TextAnchor.MiddleLeft, UiStyle.Bold);

            UiStyle.Button(container, card, "1 0", "1 0",
                UiStyle.Off(-pad - 96f, RowBlacklist), UiStyle.Off(-pad, RowBlacklist + 28f),
                "UI_GIVE_ADMIN toggle " + definition.shortname,
                blocked ? "ДА" : "НЕТ",
                blocked ? UiStyle.ColRedStrong : UiStyle.ColSecondaryBtn,
                UiStyle.SizeButton,
                blocked ? UiStyle.ColGreenTxt : UiStyle.ColSecondaryText);

            List<KeyValuePair<ItemCategory, List<ItemDefinition>>> categories = GetCategories();
            Int32 index = categories.FindIndex(x => x.Key == category);
            if (index < 0)
                return;

            Single column = (width - pad * 2f - LimitGap) * 0.5f;
            Single rightLeft = pad + column + LimitGap;

            UiStyle.Label(container, card, "0 0", "0 0",
                UiStyle.Off(pad, RowLimitLabel), UiStyle.Off(pad + column, RowLimitLabel + 16f),
                "ЛИМИТ КАТЕГОРИИ", UiStyle.SizeSmall, UiStyle.ColMuted, TextAnchor.MiddleLeft, UiStyle.Bold);

            UiStyle.InputBottom(container, card, card + ".Limit", pad, pad + column, RowLimitInput, 28f,
                limit.ToString(CultureInfo.InvariantCulture), "0 = ∞",
                "UI_GIVE_ADMIN limit " + index.ToString(CultureInfo.InvariantCulture) + " ", LimitLimit);

            UiStyle.Label(container, card, "0 0", "0 0",
                UiStyle.Off(rightLeft, RowLimitLabel), UiStyle.Off(width - pad, RowLimitLabel + 16f),
                "ЛИМИТ ПРЕДМЕТА", UiStyle.SizeSmall, UiStyle.ColMuted, TextAnchor.MiddleLeft, UiStyle.Bold);

            UiStyle.InputBottom(container, card, card + ".ItemLimit", rightLeft, width - pad, RowLimitInput, 28f,
                itemLimit.ToString(CultureInfo.InvariantCulture), "0 = ∞",
                "UI_GIVE_ADMIN itemlimit " + definition.shortname + " ", LimitLimit);
        }

        private static Single GiveBottom(Boolean admin) => (admin ? AdminStackH : 0f) + RowGive;

        private static Single QtyBottom(Boolean admin) => (admin ? AdminStackH : 0f) + RowQty;

        private void DrawQty(CuiElementContainer container, BasePlayer player, MenuState state, Boolean admin)
        {
            String card = CardPanel(admin);
            String qty = QtyPanel(admin);

            ItemDefinition definition = Selected(state);
            Single pad = UiStyle.PadCardWide;
            Single width = CardRight - CardLeft;

            if (definition == null)
            {
                UiStyle.Panel(container, card, qty, "0 0", "0 0",
                    UiStyle.Off(pad, QtyBottom(admin)), UiStyle.Off(width - pad, QtyBottom(admin) + 32f),
                    UiStyle.ColClear);
                return;
            }

            Int32 max = MaxGive(definition, player);
            Int32 amount = Mathf.Clamp(state.Amount, 1, Math.Max(1, max));

            UiStyle.CounterBottom(container, card, qty, pad, width - pad, QtyBottom(admin), 32f,
                amount, amount > 1, amount < max, CommandOf(admin) + "qty", "МАКС",
                CommandOf(admin) + "setqty");
        }

        private void DrawGive(CuiElementContainer container, BasePlayer player, MenuState state, Boolean admin)
        {
            String card = CardPanel(admin);
            String give = GivePanel(admin);

            ItemDefinition definition = Selected(state);
            Single pad = UiStyle.PadCardWide;
            Single width = CardRight - CardLeft;

            UiStyle.Panel(container, card, give, "0 0", "0 0",
                UiStyle.Off(pad, GiveBottom(admin)), UiStyle.Off(width - pad, GiveBottom(admin) + 36f),
                UiStyle.ColClear);

            if (definition == null)
                return;

            Boolean blocked = admin ? IsBlacklisted(definition.shortname) : IsBlockedFor(player.userID, definition.shortname);
            Int32 max = MaxGive(definition, player);
            Int32 amount = Mathf.Clamp(state.Amount, 1, Math.Max(1, max));
            Boolean allowed = !admin || permission.UserHasPermission(player.UserIDString, Permission);
            Boolean ready = !blocked && max > 0 && allowed;

            String caption = blocked
                ? "В ЧЁРНОМ СПИСКЕ"
                : max <= 0
                    ? "ЛИМИТ ИСЧЕРПАН"
                    : !allowed
                        ? "НЕТ ПРАВА ВЫДАЧИ"
                        : "ВЫДАТЬ " + amount.ToString("N0", CultureInfo.InvariantCulture);

            UiStyle.Primary(container, give, "0 0", "1 1", "0 0", "0 0",
                "UI_GIVE take.item " + definition.shortname + " " + amount.ToString(CultureInfo.InvariantCulture),
                caption, ready);
        }

        private void Refresh(BasePlayer player, MenuState state, Boolean admin,
            Boolean nav, Boolean head, Boolean grid, Boolean cardOnly, Boolean qtyOnly)
        {
            CuiElementContainer container = new CuiElementContainer();

            if (nav) DrawNav(container, state, admin);
            if (head) DrawHead(container, player, state, admin);
            if (grid) DrawGrid(container, state, admin);
            if (cardOnly) DrawCard(container, player, state, admin);
            if (qtyOnly)
            {
                DrawQty(container, player, state, admin);
                DrawGive(container, player, state, admin);
            }

            if (container.Count > 0)
                CuiHelper.AddUi(player, container);
        }

        [ChatCommand("give")]
        private void MainUi(BasePlayer player)
        {
            if (player == null)
                return;

            Plugin menu = Menu();
            if (menu != null && menu.Call("API_OpenTab", player, HostTab, String.Empty) is Boolean opened && opened)
                return;

            if (IsWipeCooldownActive(player))
            {
                SendNotify(player, WipeCooldownMessage());
                return;
            }

            if (!HasAccess(player))
            {
                SendNotify(player, _config.NoAccessText);
                return;
            }

            MenuState state = GetMenuState(player);
            state.Hosted = false;
            state.Category = 0;
            state.Item = String.Empty;
            state.Amount = 1;
            state.Search = String.Empty;
            state.GridPage = 0;

            DrawMenu(player, false);
        }

        [ChatCommand("giveadmin")]
        private void AdminMainUi(BasePlayer player)
        {
            if (player == null)
                return;

            if (!permission.UserHasPermission(player.UserIDString, AdminPermission))
            {
                SendNotify(player, "У вас нет доступа к настройкам GiveMenu.");
                return;
            }

            MenuState state = GetAdminMenuState(player);
            state.Category = 0;
            state.Item = String.Empty;
            state.Amount = 1;
            state.Search = String.Empty;
            state.GridPage = 0;

            DrawMenu(player, true);
        }

        [ChatCommand("givemenureset")]
        private void ResetLimitsCommand(BasePlayer player, String command, String[] args)
        {
            if (player == null)
                return;

            if (!HasAdmin(player))
            {
                SendNotify(player, "У вас нет доступа к сбросу лимитов GiveMenu.");
                return;
            }

            Int32 affected = ResetAllPlayerLimits();

            SendNotify(player,
                "Лимиты GiveMenu полностью сброшены. Игроков в истории до сброса: " +
                affected.ToString(CultureInfo.InvariantCulture) + ".");

            Puts("GiveMenu: " + player.displayName + " (" + player.UserIDString +
                 ") выполнил /givemenureset. Сброшено записей игроков: " +
                 affected.ToString(CultureInfo.InvariantCulture) + ".");
        }

        #endregion

        #region [Command]

        [ConsoleCommand("UI_GIVE_ADMIN")]
        private void GiveMenuAdminUIHandler(ConsoleSystem.Arg args)
        {
            BasePlayer player = args?.Player();
            if (player == null || !args.HasArgs())
                return;

            if (!permission.UserHasPermission(player.UserIDString, AdminPermission))
            {
                CuiHelper.DestroyUi(player, AdminLayer);
                return;
            }

            String action = args.GetString(0, String.Empty);

            if (action.Equals("switch", StringComparison.OrdinalIgnoreCase))
            {
                if (args.Args == null ||
                    args.Args.Length < 2 ||
                    !Int32.TryParse(args.GetString(1), out Int32 index))
                    return;

                List<KeyValuePair<ItemCategory, List<ItemDefinition>>> categories = GetCategories();
                if (categories.Count == 0)
                    return;

                index = Mathf.Clamp(index, 0, categories.Count - 1);

                MenuState state = GetAdminMenuState(player);
                state.Category = index;
                state.Item = String.Empty;
                state.Amount = 1;
                state.Search = String.Empty;
                state.GridPage = 0;

                Refresh(player, state, true, true, true, true, true, false);
                return;
            }

            if (action.Equals("limit", StringComparison.OrdinalIgnoreCase))
            {
                if (args.Args == null ||
                    args.Args.Length < 3 ||
                    !Int32.TryParse(args.GetString(1), out Int32 index) ||
                    !Int32.TryParse(args.GetString(2), out Int32 limit))
                {
                    SendNotify(player, "Введите целое число. 0 = без лимита.");
                    return;
                }

                List<KeyValuePair<ItemCategory, List<ItemDefinition>>> categories = GetCategories();
                if (categories.Count == 0)
                    return;

                index = Mathf.Clamp(index, 0, categories.Count - 1);
                limit = Math.Max(0, limit);

                String key = categories[index].Key.ToString();
                _config.CategoryLimits[key] = limit;
                SaveConfig();

                InfoToast?.Call("API_ShowToast", player,
                    $"Лимит {CategoryDisplayName(categories[index].Key)}: " +
                    (limit == 0 ? "без лимита" : limit.ToString("N0")));

                Refresh(player, GetAdminMenuState(player), true, false, true, false, true, false);
                return;
            }

            if (action.Equals("itemlimit", StringComparison.OrdinalIgnoreCase))
            {
                if (args.Args == null || args.Args.Length < 3)
                {
                    SendNotify(player, "Введите целое число. 0 = без лимита.");
                    return;
                }

                String target = args.GetString(1, String.Empty);
                if (String.IsNullOrWhiteSpace(target))
                    return;

                target = target.Trim();

                if (!Int32.TryParse(args.GetString(2), out Int32 itemLimit))
                {
                    SendNotify(player, "Введите целое число. 0 = без лимита.");
                    return;
                }

                ItemDefinition definition = ItemManager.FindItemDefinition(target);
                if (definition == null)
                {
                    SendNotify(player, "Предмет не найден.");
                    return;
                }

                itemLimit = Math.Max(0, itemLimit);

                if (itemLimit == 0)
                    _config.ItemLimits.Remove(target);
                else
                    _config.ItemLimits[target] = itemLimit;

                SaveConfig();

                InfoToast?.Call("API_ShowToast", player,
                    $"Лимит предмета {ItemTitle(definition)}: " +
                    (itemLimit == 0 ? "без лимита" : itemLimit.ToString("N0")));

                Refresh(player, GetAdminMenuState(player), true, false, true, false, true, false);
                return;
            }

            if (action.Equals("toggle", StringComparison.OrdinalIgnoreCase))
            {
                if (args.Args == null || args.Args.Length < 2)
                    return;

                String shortName = args.GetString(1, String.Empty);
                if (String.IsNullOrWhiteSpace(shortName))
                    return;

                ItemDefinition definition = ItemManager.FindItemDefinition(shortName);
                if (definition == null)
                {
                    SendNotify(player, "Предмет не найден.");
                    return;
                }

                Boolean enable = IsBlacklisted(shortName);
                SetItemEnabled(shortName, enable);

                Refresh(player, GetAdminMenuState(player), true, false, false, true, true, false);
                return;
            }

            if (action.Equals("page", StringComparison.OrdinalIgnoreCase))
            {
                ChangeGridPage(player, args, true);
                return;
            }

            if (action.Equals("pick", StringComparison.OrdinalIgnoreCase))
            {
                Pick(player, args, true);
                return;
            }

            if (action.Equals("qty", StringComparison.OrdinalIgnoreCase))
            {
                Quantity(player, args, true);
                return;
            }

            if (action.Equals("setqty", StringComparison.OrdinalIgnoreCase))
            {
                SetQuantity(player, args, true);
                return;
            }

            if (action.Equals("find", StringComparison.OrdinalIgnoreCase))
            {
                Find(player, args, true);
            }
        }

        [ConsoleCommand("UI_GIVE")]
        private void GiveMenuUIHandler(ConsoleSystem.Arg args)
        {
            BasePlayer player = args?.Player();
            if (player == null || !args.HasArgs())
                return;

            if (!permission.UserHasPermission(player.UserIDString, Permission))
            {
                CuiHelper.DestroyUi(player, Layer);
                return;
            }

            if (IsWipeCooldownActive(player))
            {
                SendNotify(player, WipeCooldownMessage());
                CuiHelper.DestroyUi(player, MainPanel);
                CuiHelper.DestroyUi(player, Layer);
                return;
            }

            String action = args.GetString(0, String.Empty);

            if (action.Equals("switch", StringComparison.OrdinalIgnoreCase))
            {
                if (args.Args == null ||
                    args.Args.Length < 2 ||
                    !Int32.TryParse(args.GetString(1), out Int32 index))
                    return;

                List<KeyValuePair<ItemCategory, List<ItemDefinition>>> categories = GetCategories();
                if (categories.Count == 0)
                    return;

                index = Mathf.Clamp(index, 0, categories.Count - 1);

                MenuState state = GetMenuState(player);
                state.Category = index;
                state.Item = String.Empty;
                state.Amount = 1;
                state.Search = String.Empty;
                state.GridPage = 0;

                Refresh(player, state, false, true, true, true, true, false);
                return;
            }

            if (action.Equals("page", StringComparison.OrdinalIgnoreCase))
            {
                ChangeGridPage(player, args, false);
                return;
            }

            if (action.Equals("setqty", StringComparison.OrdinalIgnoreCase))
            {
                SetQuantity(player, args, false);
                return;
            }

            if (action.Equals("adminmode", StringComparison.OrdinalIgnoreCase))
            {
                ToggleAdminMode(player);
                return;
            }

            if (action.Equals("pick", StringComparison.OrdinalIgnoreCase))
            {
                Pick(player, args, false);
                return;
            }

            if (action.Equals("qty", StringComparison.OrdinalIgnoreCase))
            {
                Quantity(player, args, false);
                return;
            }

            if (action.Equals("find", StringComparison.OrdinalIgnoreCase))
            {
                Find(player, args, false);
                return;
            }

            if (action.Equals("take.item", StringComparison.OrdinalIgnoreCase))
            {
                if (args.Args == null || args.Args.Length < 3)
                    return;

                String shortName = args.GetString(1, String.Empty);
                if (String.IsNullOrWhiteSpace(shortName) ||
                    !Int32.TryParse(args.GetString(2), out Int32 amount) ||
                    amount <= 0)
                {
                    SendNotify(player, "Укажите корректное количество.");
                    return;
                }

                if (IsBlacklisted(shortName) && !IsPersonallyAllowed(player.userID, shortName))
                {
                    SendNotify(player, "Этот предмет запрещён для выдачи.");
                    return;
                }

                if (IsPersonallyBlocked(player.userID, shortName))
                {
                    SendNotify(player, "Этот предмет запрещён вам для выдачи.");
                    return;
                }

                ItemDefinition definition = ItemManager.FindItemDefinition(shortName);
                if (definition == null)
                {
                    SendNotify(player, "Предмет не найден.");
                    return;
                }

                PlayerData data = GetPlayerData(player.userID);
                ItemCategory category = definition.category;
                Boolean unlimited = IsUnlimited(player);

                Int32 currentAmount = GetCategoryAmount(data, category);
                Int32 categoryLimit = GetCategoryLimit(category);
                Int64 newAmount = (Int64)currentAmount + amount;

                Int32 currentItem = GetItemAmount(data, shortName);
                Int32 itemLimit = GetItemLimit(shortName);
                Int64 newItem = (Int64)currentItem + amount;

                if (itemLimit > 0 && newItem > itemLimit && !unlimited)
                {
                    Int32 remaining = Math.Max(0, itemLimit - currentItem);
                    SendNotify(
                        player,
                        $"Лимит предмета {ItemTitle(definition)}: " +
                        $"{currentItem:N0}/{itemLimit:N0}. Доступно ещё: {remaining:N0}.");
                    return;
                }

                if (categoryLimit > 0 && newAmount > categoryLimit && !unlimited)
                {
                    Int32 remaining = Math.Max(0, categoryLimit - currentAmount);
                    SendNotify(
                        player,
                        $"Лимит категории {CategoryDisplayName(category)}: " +
                        $"{currentAmount:N0}/{categoryLimit:N0}. Доступно ещё: {remaining:N0}.");
                    return;
                }

                Item item = ItemManager.CreateByName(shortName, amount);
                if (item is null)
                {
                    SendNotify(player, "Предмет не найден.");
                    return;
                }

                if (!player.inventory.GiveItem(item))
                {
                    item.Remove(0f);
                    SendNotify(player, "В инвентаре нет места.");
                    return;
                }

                SetCategoryAmount(data, category, (Int32)newAmount);
                SetItemAmount(data, shortName, (Int32)newItem);
                RequestPlayerSave();

                SendNotify(
                    player,
                    $"Получено: {amount:N0} × {item.info.displayName.english}");

                RefreshAfterGive(player);
            }
        }

        private void RefreshAfterGive(BasePlayer player)
        {
            if (_menuStates.ContainsKey(player.userID))
                Refresh(player, GetMenuState(player), false, false, true, false, true, false);

            if (_adminMenuStates.ContainsKey(player.userID))
                Refresh(player, GetAdminMenuState(player), true, false, true, false, true, false);
        }

        private void ChangeGridPage(BasePlayer player, ConsoleSystem.Arg args, Boolean admin)
        {
            if (player == null || args == null || args.Args == null || args.Args.Length < 2)
                return;

            Int32 requested;
            if (!Int32.TryParse(args.GetString(1, String.Empty), out requested))
                return;

            MenuState state = StateOf(player, admin);
            Boolean searchedAll;
            List<ItemDefinition> items = VisibleItems(state, admin, out searchedAll);
            Int32 pageCount = Math.Max(1, (items.Count + GridItemsPerPage - 1) / GridItemsPerPage);
            state.GridPage = Mathf.Clamp(requested, 0, pageCount - 1);
            state.Item = String.Empty;
            state.Amount = 1;

            Refresh(player, state, admin, false, false, true, true, false);
        }

        private void Pick(BasePlayer player, ConsoleSystem.Arg args, Boolean admin)
        {
            if (args.Args == null || args.Args.Length < 2)
                return;

            String shortName = args.GetString(1, String.Empty);
            if (String.IsNullOrWhiteSpace(shortName))
                return;

            ItemDefinition definition = ItemManager.FindItemDefinition(shortName);
            if (definition == null)
                return;

            MenuState state = StateOf(player, admin);
            String previous = state.Item;
            state.Item = definition.shortname;
            state.Amount = 1;

            CuiElementContainer container = new CuiElementContainer();

            if (!String.Equals(previous, state.Item, StringComparison.OrdinalIgnoreCase))
                RedrawSlot(container, state, admin, previous);

            RedrawSlot(container, state, admin, state.Item);
            DrawCard(container, player, state, admin);

            CuiHelper.AddUi(player, container);
        }

        private void SetQuantity(BasePlayer player, ConsoleSystem.Arg args, Boolean admin)
        {
            if (args.Args == null || args.Args.Length < 2)
                return;

            MenuState state = StateOf(player, admin);
            ItemDefinition definition = Selected(state);
            if (definition == null)
                return;

            Int32 max = Math.Max(1, MaxGive(definition, player));

            Int32 typed;
            if (!Int32.TryParse(args.GetString(1, String.Empty).Trim(), out typed))
                typed = state.Amount;

            state.Amount = Mathf.Clamp(typed, 1, max);

            Refresh(player, state, admin, false, false, false, false, true);
        }

        private void Quantity(BasePlayer player, ConsoleSystem.Arg args, Boolean admin)
        {
            if (args.Args == null || args.Args.Length < 2)
                return;

            MenuState state = StateOf(player, admin);
            ItemDefinition definition = Selected(state);
            if (definition == null)
                return;

            Int32 max = Math.Max(1, MaxGive(definition, player));
            String value = args.GetString(1, String.Empty);

            if (value.Equals("max", StringComparison.OrdinalIgnoreCase))
                state.Amount = max;
            else if (Int32.TryParse(value, out Int32 step))
                state.Amount = Mathf.Clamp(state.Amount + step, 1, max);
            else
                return;

            Refresh(player, state, admin, false, false, false, false, true);
        }

        private void Find(BasePlayer player, ConsoleSystem.Arg args, Boolean admin)
        {
            MenuState state = StateOf(player, admin);

            String query = args.Args != null && args.Args.Length > 1
                ? String.Join(" ", args.Args.Skip(1).Select(x => x.ToString()))
                : String.Empty;

            state.Search = query.Trim();
            state.Item = String.Empty;
            state.Amount = 1;
            state.GridPage = 0;

            Refresh(player, state, admin, false, true, true, true, false);
        }

        #endregion

        #region [Functional]

        private List<KeyValuePair<ItemCategory, List<ItemDefinition>>> GetCategories()
        {
            if (_categories != null && _categories.Count > 0)
                return _categories;

            if (m_ItemDefinitionsPerCategory == null)
                return new List<KeyValuePair<ItemCategory, List<ItemDefinition>>>();

            _categories = m_ItemDefinitionsPerCategory
                .Where(x => x.Value != null && x.Value.Count > 0)
                .OrderBy(x => CategorySortOrder(x.Key))
                .ThenBy(x => x.Key.ToString(), StringComparer.OrdinalIgnoreCase)
                .ToList();

            return _categories;
        }

        private MenuState GetMenuState(BasePlayer player)
        {
            if (player == null)
                return new MenuState();

            if (!_menuStates.TryGetValue(player.userID, out MenuState state))
            {
                state = new MenuState();
                _menuStates[player.userID] = state;
            }

            state.UserId = player.userID;
            return state;
        }

        private MenuState GetAdminMenuState(BasePlayer player)
        {
            if (player == null)
                return new MenuState();

            if (!_adminMenuStates.TryGetValue(player.userID, out MenuState state))
            {
                state = new MenuState();
                _adminMenuStates[player.userID] = state;
            }

            state.UserId = player.userID;
            return state;
        }

        private void SetItemEnabled(String shortName, Boolean enabled)
        {
            if (String.IsNullOrWhiteSpace(shortName))
                return;

            shortName = shortName.Trim();

            if (_config.Blacklist == null)
                _config.Blacklist = new List<String>();

            if (enabled)
            {
                _config.Blacklist.RemoveAll(x =>
                    String.Equals(x, shortName, StringComparison.OrdinalIgnoreCase));
            }
            else if (!_config.Blacklist.Any(x =>
                         String.Equals(x, shortName, StringComparison.OrdinalIgnoreCase)))
            {
                _config.Blacklist.Add(shortName);
            }

            _config.Blacklist = _config.Blacklist
                .Where(x => !String.IsNullOrWhiteSpace(x))
                .Select(x => x.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(x => x, StringComparer.OrdinalIgnoreCase)
                .ToList();

            RebuildBlacklist();
            SaveConfig();
        }

        private Dictionary<UInt64, HashSet<String>> LoadPersonalList(String file)
        {
            Dictionary<UInt64, List<String>> stored = null;
            try
            {
                stored = Interface.Oxide.DataFileSystem.ReadObject<Dictionary<UInt64, List<String>>>(file);
            }
            catch (Exception ex)
            {
                PrintError("GiveMenu: не удалось прочитать " + file + ": " + ex.Message);
            }

            Dictionary<UInt64, HashSet<String>> lists = new Dictionary<UInt64, HashSet<String>>();
            if (stored == null)
                return lists;

            foreach (KeyValuePair<UInt64, List<String>> entry in stored)
            {
                if (entry.Value == null)
                    continue;

                HashSet<String> items = new HashSet<String>(StringComparer.OrdinalIgnoreCase);
                foreach (String item in entry.Value)
                    if (!String.IsNullOrWhiteSpace(item))
                        items.Add(item.Trim());

                if (items.Count > 0)
                    lists[entry.Key] = items;
            }

            return lists;
        }

        private void SavePersonalList(String file, Dictionary<UInt64, HashSet<String>> lists)
        {
            Dictionary<UInt64, List<String>> stored = new Dictionary<UInt64, List<String>>();
            foreach (KeyValuePair<UInt64, HashSet<String>> entry in lists)
                if (entry.Value != null && entry.Value.Count > 0)
                    stored[entry.Key] = entry.Value.OrderBy(x => x, StringComparer.OrdinalIgnoreCase).ToList();

            Interface.Oxide.DataFileSystem.WriteObject(file, stored);
        }

        private void LoadPersonalBlacklist()
        {
            _personalBlacklist = LoadPersonalList(PersonalBlacklistFile);
            _personalAllowlist = LoadPersonalList(PersonalAllowlistFile);
        }

        private static Boolean InPersonalList(Dictionary<UInt64, HashSet<String>> lists, UInt64 userId, String shortName)
        {
            return userId != 0UL &&
                   !String.IsNullOrWhiteSpace(shortName) &&
                   lists.TryGetValue(userId, out HashSet<String> items) &&
                   items.Contains(shortName.Trim());
        }

        private Boolean IsPersonallyBlocked(UInt64 userId, String shortName)
        {
            return InPersonalList(_personalBlacklist, userId, shortName);
        }

        private Boolean IsPersonallyAllowed(UInt64 userId, String shortName)
        {
            return InPersonalList(_personalAllowlist, userId, shortName);
        }

        private Boolean IsBlockedFor(UInt64 userId, String shortName)
        {
            return (IsBlacklisted(shortName) && !IsPersonallyAllowed(userId, shortName)) ||
                   IsPersonallyBlocked(userId, shortName);
        }

        private const String BlockUsage =
            "Персональные списки GiveMenu:\n" +
            "/giveblock add <SteamID или ник> <shortname> [shortname ...] — запретить предметы игроку\n" +
            "/giveblock remove <SteamID или ник> <shortname> [shortname ...] — снять персональный запрет\n" +
            "/giveblock allow <SteamID или ник> <shortname> [shortname ...] — разрешить игроку предметы из общего чёрного списка\n" +
            "/giveblock disallow <SteamID или ник> <shortname> [shortname ...] — убрать персональное разрешение\n" +
            "/giveblock list <SteamID или ник> — запреты и разрешения игрока\n" +
            "/giveblock clear <SteamID или ник> — снять все персональные запреты и разрешения\n" +
            "В консоли сервера то же самое: givemenu.block add|remove|allow|disallow|list|clear ...";

        [ChatCommand("giveblock")]
        private void BlockChatCommand(BasePlayer player, String command, String[] args)
        {
            if (player == null)
                return;

            if (!HasAdmin(player))
            {
                SendNotify(player, "У вас нет доступа к персональным спискам GiveMenu.");
                return;
            }

            SendReply(player, RunBlockCommand(args ?? new String[0], player.displayName + " (" + player.UserIDString + ")"));
        }

        [ConsoleCommand("givemenu.block")]
        private void BlockConsoleCommand(ConsoleSystem.Arg arg)
        {
            if (arg == null)
                return;

            BasePlayer player = arg.Player();
            if (arg.Connection != null && (player == null || !HasAdmin(player)))
            {
                arg.ReplyWith("Нет доступа к персональным спискам GiveMenu.");
                return;
            }

            String[] args = new String[arg.Args?.Length ?? 0];
            for (Int32 i = 0; i < args.Length; i++)
                args[i] = arg.GetString(i, String.Empty);

            arg.ReplyWith(RunBlockCommand(args,
                player != null ? player.displayName + " (" + player.UserIDString + ")" : "консоль сервера"));
        }

        private static String PersonalItems(Dictionary<UInt64, HashSet<String>> lists, UInt64 userId)
        {
            return lists.TryGetValue(userId, out HashSet<String> items) && items.Count > 0
                ? String.Join(", ", items.OrderBy(x => x, StringComparer.OrdinalIgnoreCase).Select(BlockItemLabel))
                : null;
        }

        private String RunBlockCommand(String[] args, String actor)
        {
            if (args.Length < 2)
                return BlockUsage;

            String action = args[0].Trim().ToLowerInvariant();
            if (action != "add" && action != "remove" && action != "allow" && action != "disallow" &&
                action != "list" && action != "clear")
                return BlockUsage;

            String error;
            IPlayer target = FindBlockTarget(args[1], out error);
            if (target == null)
                return error;

            UInt64 userId = UInt64.Parse(target.Id, CultureInfo.InvariantCulture);
            String who = (String.IsNullOrWhiteSpace(target.Name) ? "игрок" : target.Name) + " (" + target.Id + ")";

            if (action == "list")
            {
                String blocked = PersonalItems(_personalBlacklist, userId);
                String allowed = PersonalItems(_personalAllowlist, userId);
                if (blocked == null && allowed == null)
                    return "У игрока " + who + " нет персональных запретов и разрешений.";

                return "Игрок " + who + ":" +
                       (blocked != null ? "\nзапрещено: " + blocked + "." : String.Empty) +
                       (allowed != null ? "\nразрешено сверх общего чёрного списка: " + allowed + "." : String.Empty);
            }

            if (action == "clear")
            {
                Int32 removed = (_personalBlacklist.TryGetValue(userId, out HashSet<String> b) ? b.Count : 0) +
                                (_personalAllowlist.TryGetValue(userId, out HashSet<String> a) ? a.Count : 0);
                if (removed == 0)
                    return "У игрока " + who + " нет персональных запретов и разрешений.";

                _personalBlacklist.Remove(userId);
                _personalAllowlist.Remove(userId);
                SavePersonalList(PersonalBlacklistFile, _personalBlacklist);
                SavePersonalList(PersonalAllowlistFile, _personalAllowlist);
                Puts("GiveMenu: " + actor + " снял все персональные запреты и разрешения (" + removed.ToString(CultureInfo.InvariantCulture) + ") с " + who + ".");
                return "Сняты все персональные запреты и разрешения игрока " + who + ": " + removed.ToString(CultureInfo.InvariantCulture) + ".";
            }

            if (args.Length < 3)
                return BlockUsage;

            Boolean allowList = action == "allow" || action == "disallow";
            Boolean adding = action == "add" || action == "allow";
            Dictionary<UInt64, HashSet<String>> lists = allowList ? _personalAllowlist : _personalBlacklist;
            Dictionary<UInt64, HashSet<String>> opposite = allowList ? _personalBlacklist : _personalAllowlist;
            lists.TryGetValue(userId, out HashSet<String> items);
            opposite.TryGetValue(userId, out HashSet<String> oppositeItems);

            List<String> changed = new List<String>();
            List<String> skipped = new List<String>();
            List<String> notes = new List<String>();
            Boolean oppositeChanged = false;
            for (Int32 i = 2; i < args.Length && i < 52; i++)
            {
                String raw = args[i]?.Trim();
                if (String.IsNullOrEmpty(raw))
                    continue;

                ItemDefinition definition = ItemManager.FindItemDefinition(raw);
                if (definition == null)
                {
                    skipped.Add(raw + " — нет такого предмета");
                    continue;
                }

                String label = BlockItemLabel(definition.shortname);
                if (adding)
                {
                    if (items == null)
                    {
                        items = new HashSet<String>(StringComparer.OrdinalIgnoreCase);
                        lists[userId] = items;
                    }

                    if (!items.Add(definition.shortname))
                    {
                        skipped.Add(label + (allowList ? " — уже разрешён" : " — уже запрещён"));
                        continue;
                    }

                    changed.Add(label);
                    if (oppositeItems != null && oppositeItems.Remove(definition.shortname))
                    {
                        oppositeChanged = true;
                        notes.Add(label + (allowList ? " убран из персональных запретов" : " убран из персональных разрешений"));
                    }

                    if (allowList && !IsBlacklisted(definition.shortname))
                        notes.Add(label + " сейчас не в общем чёрном списке и доступен всем");
                }
                else if (items != null && items.Remove(definition.shortname))
                {
                    changed.Add(label);
                }
                else
                {
                    skipped.Add(label + (allowList ? " — не был разрешён" : " — не был запрещён"));
                }
            }

            if (items != null && items.Count == 0)
                lists.Remove(userId);
            if (oppositeItems != null && oppositeItems.Count == 0)
                opposite.Remove(userId);

            String verb = action == "add"
                ? "Запрещено игроку "
                : action == "remove"
                    ? "Снят персональный запрет игроку "
                    : action == "allow"
                        ? "Разрешено сверх общего чёрного списка игроку "
                        : "Убрано персональное разрешение игроку ";

            if (changed.Count > 0)
            {
                SavePersonalList(allowList ? PersonalAllowlistFile : PersonalBlacklistFile, lists);
                if (oppositeChanged)
                    SavePersonalList(allowList ? PersonalBlacklistFile : PersonalAllowlistFile, opposite);
                Puts("GiveMenu: " + actor + ": " + verb + who + ": " + String.Join(", ", changed) + ".");
            }

            String result = changed.Count > 0
                ? verb + who + ": " + String.Join(", ", changed) + "."
                : "Изменений нет.";

            if (notes.Count > 0)
                result += "\n" + String.Join(".\n", notes) + ".";
            if (skipped.Count > 0)
                result += "\nПропущено: " + String.Join("; ", skipped) + ".";

            return result;
        }

        private IPlayer FindBlockTarget(String query, out String error)
        {
            error = null;
            query = query?.Trim() ?? String.Empty;

            if (query.Length == 17 && query.StartsWith("7656119", StringComparison.Ordinal) && UInt64.TryParse(query, out UInt64 _))
            {
                IPlayer known = covalence.Players.FindPlayerById(query);
                if (known != null)
                    return known;

                error = "Игрок " + query + " ещё не заходил на сервер.";
                return null;
            }

            if (query.Length < 2)
            {
                error = "Укажите SteamID64 или хотя бы два символа ника.";
                return null;
            }

            List<IPlayer> found = covalence.Players.FindPlayers(query)
                .Where(x => x != null && x.Id != null && x.Id.Length == 17 && UInt64.TryParse(x.Id, out UInt64 _))
                .Take(6)
                .ToList();

            if (found.Count == 1)
                return found[0];

            IPlayer exact = found.FirstOrDefault(x => String.Equals(x.Name, query, StringComparison.OrdinalIgnoreCase));
            if (exact != null)
                return exact;

            error = found.Count == 0
                ? "Игрок «" + query + "» не найден. Укажите SteamID64."
                : "Найдено несколько игроков, укажите SteamID64: " +
                  String.Join(", ", found.Take(5).Select(x => x.Name + " (" + x.Id + ")")) + ".";
            return null;
        }

        private static String BlockItemLabel(String shortName)
        {
            ItemDefinition definition = ItemManager.FindItemDefinition(shortName);
            return definition == null ? shortName : ItemTitle(definition) + " [" + shortName + "]";
        }

        private Boolean IsBlacklisted(String shortName)
        {
            return !String.IsNullOrWhiteSpace(shortName) &&
                   _blacklist != null &&
                   _blacklist.Contains(shortName.Trim());
        }

        private Int32 GetCategoryLimit(ItemCategory category)
        {
            String key = category.ToString();

            if (_config?.CategoryLimits != null &&
                _config.CategoryLimits.TryGetValue(key, out Int32 limit))
                return Math.Max(0, limit);

            return Math.Max(0, _config?.DefaultCategoryLimit ?? 0);
        }

        private Int32 GetItemLimit(String shortName)
        {
            if (String.IsNullOrWhiteSpace(shortName) || _config?.ItemLimits == null)
                return 0;

            return _config.ItemLimits.TryGetValue(shortName.Trim(), out Int32 limit)
                ? Math.Max(0, limit)
                : 0;
        }

        private Int32 GetItemAmount(PlayerData data, String shortName)
        {
            if (data == null || String.IsNullOrWhiteSpace(shortName))
                return 0;

            if (data.ItemAmounts == null)
                data.ItemAmounts = new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);

            return data.ItemAmounts.TryGetValue(shortName.Trim(), out Int32 amount)
                ? Math.Max(0, amount)
                : 0;
        }

        private void SetItemAmount(PlayerData data, String shortName, Int32 amount)
        {
            if (data == null || String.IsNullOrWhiteSpace(shortName))
                return;

            if (data.ItemAmounts == null)
                data.ItemAmounts = new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);

            data.ItemAmounts[shortName.Trim()] = Math.Max(0, amount);
        }

        private Int32 GetCategoryAmount(PlayerData data, ItemCategory category)
        {
            if (data == null)
                return 0;

            if (data.CategoryAmounts == null)
                data.CategoryAmounts = new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);

            String key = category.ToString();
            return data.CategoryAmounts.TryGetValue(key, out Int32 amount)
                ? Math.Max(0, amount)
                : 0;
        }

        private void SetCategoryAmount(PlayerData data, ItemCategory category, Int32 amount)
        {
            if (data == null)
                return;

            if (data.CategoryAmounts == null)
                data.CategoryAmounts = new Dictionary<String, Int32>(StringComparer.OrdinalIgnoreCase);

            data.CategoryAmounts[category.ToString()] = Math.Max(0, amount);
        }

        private Int32 CategorySortOrder(ItemCategory category)
        {
            String name = category.ToString();

            if (name.Equals("Weapon", StringComparison.OrdinalIgnoreCase)) return 0;
            if (name.Equals("Ammunition", StringComparison.OrdinalIgnoreCase)) return 1;
            if (name.Equals("Attire", StringComparison.OrdinalIgnoreCase)) return 2;
            if (name.Equals("Tool", StringComparison.OrdinalIgnoreCase)) return 3;
            if (name.Equals("Medical", StringComparison.OrdinalIgnoreCase)) return 4;
            if (name.Equals("Food", StringComparison.OrdinalIgnoreCase)) return 5;
            if (name.Equals("Resources", StringComparison.OrdinalIgnoreCase)) return 6;
            if (name.Equals("Component", StringComparison.OrdinalIgnoreCase)) return 7;
            if (name.Equals("Construction", StringComparison.OrdinalIgnoreCase)) return 8;
            if (name.Equals("Electrical", StringComparison.OrdinalIgnoreCase)) return 9;
            if (name.Equals("Traps", StringComparison.OrdinalIgnoreCase)) return 10;
            if (name.Equals("Fun", StringComparison.OrdinalIgnoreCase)) return 11;
            if (name.Equals("Items", StringComparison.OrdinalIgnoreCase)) return 12;

            return 100;
        }

        private String CategoryDisplayName(ItemCategory category)
        {
            String name = category.ToString();

            if (name.Equals("Weapon", StringComparison.OrdinalIgnoreCase)) return "ОРУЖИЕ";
            if (name.Equals("Ammunition", StringComparison.OrdinalIgnoreCase)) return "ПАТРОНЫ";
            if (name.Equals("Attire", StringComparison.OrdinalIgnoreCase)) return "ОДЕЖДА";
            if (name.Equals("Tool", StringComparison.OrdinalIgnoreCase)) return "ИНСТРУМЕНТЫ";
            if (name.Equals("Medical", StringComparison.OrdinalIgnoreCase)) return "МЕДИЦИНА";
            if (name.Equals("Food", StringComparison.OrdinalIgnoreCase)) return "ЕДА";
            if (name.Equals("Resources", StringComparison.OrdinalIgnoreCase)) return "РЕСУРСЫ";
            if (name.Equals("Component", StringComparison.OrdinalIgnoreCase)) return "КОМПОНЕНТЫ";
            if (name.Equals("Construction", StringComparison.OrdinalIgnoreCase)) return "СТРОЙКА";
            if (name.Equals("Electrical", StringComparison.OrdinalIgnoreCase)) return "ЭЛЕКТРИКА";
            if (name.Equals("Traps", StringComparison.OrdinalIgnoreCase)) return "ЛОВУШКИ";
            if (name.Equals("Fun", StringComparison.OrdinalIgnoreCase)) return "РАЗНОЕ";
            if (name.Equals("Items", StringComparison.OrdinalIgnoreCase)) return "ПРЕДМЕТЫ";
            if (name.Equals("Misc", StringComparison.OrdinalIgnoreCase)) return "ПРОЧЕЕ";

            return name.ToUpperInvariant();
        }

        private void SendNotify(BasePlayer player, String message)
        {
            if (player == null)
                return;

            InfoToast?.Call("API_ShowToast", player, message);
        }

        private static String ShortText(String value, Int32 max)
        {
            if (String.IsNullOrEmpty(value))
                return String.Empty;

            if (value.Length <= max)
                return value;

            return value.Substring(0, Math.Max(1, max - 1)) + "…";
        }

        private static String F(Single value) =>
            value.ToString("0.##", CultureInfo.InvariantCulture);

        #endregion
    }
}
