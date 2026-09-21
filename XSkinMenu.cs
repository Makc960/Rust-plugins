using Facepunch;
using System.Text;
using Oxide.Core.Libraries;
using System.Linq;
using System.Collections.Generic;
using System;
using Oxide.Game.Rust.Cui;
using UnityEngine;
using Oxide.Core.Plugins;
using System.Text.RegularExpressions;
using System.Collections;
using Oxide.Core;
using Newtonsoft.Json;
		   		 		  						  	   		   		 		  		 			   					  	 	 
namespace Oxide.Plugins 
{
    [Info("XSkinMenu", "Monster", "1.8.6")]
    class XSkinMenu : RustPlugin
    {
		private const bool LanguageEnglish = false;
		private HashSet<ulong> _adminAndVipSkins = new HashSet<ulong>();
		private Dictionary<string, List<ulong>> StoredDataSkins = new Dictionary<string, List<ulong>>();
		
		private void SaveData(BasePlayer player)
		{
			ulong userID = player.userID;
			
			if(StoredData.ContainsKey(userID))
				Interface.Oxide.DataFileSystem.WriteObject($"XDataSystem/XSkinMenu/UserSettings/{userID}", StoredData[userID]);
		}
		
		private void OnItemPickup(Item item, BasePlayer player)
		{
			if(item == null || player == null || player.IsNpc) return;
			
			if(!StoredData.TryGetValue(player.userID, out Data data) || !data.ChangeSP) return;

			string shortname = item.info.shortname;

			if(!data.Skins.TryGetValue(shortname, out ulong skin)) return;
			if(config.Setting.ReskinConfig && !_items.ContainsKey(shortname)) return;
			if(!permission.UserHasPermission(player.UserIDString, permPickup)) return;

			if(ersK.TryGetValue(shortname, out Dictionary<ulong, string> redirects) && redirects.ContainsKey(skin))
				NextTick(() => SetSkinCraftGive(player, item, true));
			else
				SetSkinCraftGive(player, item);
		}
		   		 		  						  	   		   		 		  		 			   					  	 	 
        private class SkinConfig
        {
			internal class SteamSetting
			{
				[JsonProperty("Steam API Key")] public string APIKey;
				
				[JsonProperty(LanguageEnglish
					? "List of community skins that will not be automatically deleted by the plugin. ( For example, these are skins that are not publicly accessible — skins featuring your server design. )"
					: "Список комьюнити скинов, которые не будут автоматически удалены плагином. ( Например, это скины с не публичным доступом - скины с дизайном вашего сервера  )")] public HashSet<ulong> IgnoredCommunitySkinsList;
			}
			
			internal class GeneralSetting
			{
				[JsonProperty(LanguageEnglish
					? "-->   Use ImageLibrary to display Community skins. [ Skins will be displayed in the UI almost instantly. ]"
					: "-->   Использовать ImageLibrary для отображения Комьюнити скинов. [ Отображение скинов в UI будет практически мгновенным. ]")] public bool UseImageLibrary;
				[JsonProperty(LanguageEnglish
					? "-->   Which API to use for ImageLibrary - [ True - SkyAPI (beautiful images) | False - SteamAPI ]"
					: "-->   Какой API использовать для ImageLibrary - [ True - SkyAPI (красивые картинки) | False - SteamAPI ]")] public bool ImageAPIOption = true;
				
				//FP_TOS
				[JsonProperty(LanguageEnglish
					? "[ August 7, 2025 ] - Fully comply with the new Facepunch rules. The plugin will automatically remove skins from Facepunch and no longer allow them. ( false - the plugin will work as it has for the last years )"
					: "[ 7 Августа 2025 ] - Полностью соответствовать новым правилам Facepunch. Плагин автоматически удалит скины от Facepunch и больше не разрешит их использовать. ( false - плагин будет работать как и работал последние годы )")] public bool FacepunchTOS;
				
				[JsonProperty(LanguageEnglish ? "Generate/Check and add new skins accepted by developers or made for twitch drops" : "Сгенерировать/Проверять и добавлять новые скины принятые разработчиками или сделаные для твич дропсов")] public bool UpdateSkins;
				[JsonProperty(LanguageEnglish ? "Generate/Check and add new skins added by developers [ For example, a skin for hazmatsuit ]" : "Сгенерировать/Проверять и добавлять новые скины добавленные разработчиками [ К примеру скин на хазмат ]")] public bool UpdateSkinsFacepunch;
				[JsonProperty(LanguageEnglish ? "Automatically add new items to the configuration" : "Автоматически добавлять в конфигурацию новые предметы")] public bool AutoAddNewItems;
				[JsonProperty(LanguageEnglish ? "Propagate blacklisted skins to repair bench" : "Распространять черный список скинов на ремонтный верстак")] public bool RepairBench;
				[JsonProperty(LanguageEnglish ? "Forbid changing the skin of an item that is not in the config" : "Запретить менять скин предмета которого нет в конфиге")] public bool ReskinConfig;
				[JsonProperty(LanguageEnglish ? "Change skins to items after player respawns" : "Изменять скины на предметы после респавна игрока")] public bool ReskinRespawn;
				[JsonProperty(LanguageEnglish ? "Enable default skin kits" : "Включить стандартные наборы скинов")] public bool EnableDefaultKits = true;
				[JsonProperty(LanguageEnglish ? "Enable personal skin kits" : "Включить личные наборы скинов")] public bool EnableCustomKits = true;
				[JsonProperty(LanguageEnglish ? "Reissue an active item. [ Visually, the skin will be visible on the item at once ]" : "Перевыдавать активный предмет. [ Визуально скин будет виден на предмете сразу ]")] public bool ReissueActiveItem = true;
				[JsonProperty(LanguageEnglish ? "Shift UI if the above option is enabled" : "Сдвинуть UI если включен параметр выше")] public bool ShiftUI = true;
				[JsonProperty(LanguageEnglish ? "1.1 Reset Admin skins from items if they are used by a player without permission" : "1.1 Сбрасывать Админ скины с предметов если их использует игрок без разрешения")] public bool DeleteAdminSkins1 = true;
				[JsonProperty(LanguageEnglish ? "1.2 Do not reset Admin skin if the player has it in the Steam inventory" : "1.2 Не сбрасывать Админ скин если он есть у игрока в инвентаре Steam")] public bool DeleteAdminSkins2 = true;
				[JsonProperty(LanguageEnglish ? "2.1 Reset Vip skins from items if they are used by a player without permission" : "2.1 Сбрасывать Вип скины с предметов если их использует игрок без разрешения")] public bool DeleteVipSkins1 = true;
				[JsonProperty(LanguageEnglish ? "2.2 Do not reset Vip skin if the player has it in the Steam inventory" : "2.2 Не сбрасывать Вип скин если он есть у игрока в инвентаре Steam")] public bool DeleteVipSkins2 = true;
				[JsonProperty(LanguageEnglish ? "List of commands to open the menu - 1" : "Список команд для открытия меню - 1")] public List<string> SkinCommandList;
				[JsonProperty(LanguageEnglish ? "List of commands to open the menu - 2" : "Список команд для открытия меню - 2")] public List<string> SkinKitCommandList;
				[JsonProperty(LanguageEnglish ? "List of commands to open the menu - 3" : "Список команд для открытия меню - 3")] public List<string> SkinEntityCommandList;
				[JsonProperty(LanguageEnglish ? "List of commands to open the menu - 4" : "Список команд для открытия меню - 4")] public List<string> SkinItemCommandList;
				[JsonProperty(LanguageEnglish ? "Blacklist of skins that cannot be changed. [ For example: fire gloves, fire hatchet ]" : "Черный список скинов которые нельзя изменить. [ Например: огненные перчатки, огненный топор ]]")] public List<ulong> Blacklist = new List<ulong>();
				[JsonProperty(LanguageEnglish ? "List Admin skins" : "Список Админ скинов")] public Dictionary<string, List<ulong>> AdminSkins = new Dictionary<string, List<ulong>>();
				[JsonProperty(LanguageEnglish ? "List Vip skins" : "Список Вип скинов")] public Dictionary<string, List<ulong>> VipSkins = new Dictionary<string, List<ulong>>();
			}
			
			internal class PlayerSetting
			{
				[JsonProperty(LanguageEnglish ? "Change item skin in inventory after selecting skin in menu" : "Изменить скин предмета в инвентаре после выбора скина в меню")] public bool ChangeSI;
				[JsonProperty(LanguageEnglish ? "Change item skin in inventory after removing skin in menu" : "Изменить скин предмета в инвентаре после удаления скина в меню")] public bool ChangeSCL;
				[JsonProperty(LanguageEnglish ? "Change skin on installed items/constructions   [ /skinentity ]" : "Изменить скин на установленных предметах/конструкциях   [ /skinentity ]")] public bool ChangeSE;
				[JsonProperty(LanguageEnglish ? "Allow friends to change the skin on items/constructions you installed   [ /skinentity ]" : "Разрешить друзьям изменить скин на установленных вами предметах/конструкциях   [ /skinentity ]")] public bool ChangeF;
				[JsonProperty(LanguageEnglish ? "Change item skin when it is placed in the inventory by any means" : "Изменить скин предмета когда он любым способом попал в инвентарь")] public bool ChangeSG;
				[JsonProperty(LanguageEnglish ? "Change item skin only when pickup" : "Изменить скин предмета только при его подборе")] public bool ChangeSP;
				[JsonProperty(LanguageEnglish ? "Do not reset item skin for which no skin is selected when it enters the inventory" : "Не сбрасывать скин предмета, для которого не выбран скин, когда он попадает в инвентарь")] public bool ChangeSGN;
				[JsonProperty(LanguageEnglish ? "Change item skin when crafting" : "Изменить скин предмета при крафте")] public bool ChangeSC;
				[JsonProperty(LanguageEnglish ? "Use skins with a spray can" : "Использовать скины с помощью баллончика")] public bool UseSprayC;
				[JsonProperty(LanguageEnglish ? "Enable sound effects in the menu   [ Clicks ]" : "Включить звуковые эффекты в меню   [ Щелчки ]")] public bool UseSoundE;
				[JsonProperty(LanguageEnglish ? "[ True - Comfort menu | False - Default menu ]" : "[ True - Комфортное меню | False - Стандартное меню ]")] public bool Comfort;
			}
			
			internal class GUISetting
			{
				[JsonProperty(LanguageEnglish ? "Layer UI - [ Overlay - above inventory | Hud - under inventory (to view installed skins without closing the menu) ]" : "Слой UI - [ Overlay - поверх инвентаря | Hud - под инвентарем (для просмотра установленных скинов не закрывая меню) ]")] public string LayerUI = "Overlay";
				[JsonProperty(LanguageEnglish ? "Refresh UI page after skin selection" : "Обновлять UI страницу после выбора скина")] public bool SkinUP;
				[JsonProperty(LanguageEnglish ? "Refresh UI page after skin removal" : "Обновлять UI страницу после удаления скина")] public bool DelSkinUP;
				[JsonProperty(LanguageEnglish ? "Display selected skins on homepage" : "Отображать выбранные скины на главной")] public bool MainSkin;
				[JsonProperty(LanguageEnglish ? "Display button to reset, all selected skins" : "Отображать кнопку для сброса всех выбранных скинов")] public bool ButtonClear;
				[JsonProperty(LanguageEnglish ? "Display pages" : "Отображать страницы")] public bool Page;
				[JsonProperty(LanguageEnglish ? "Display the button  - Comfort menu [ + ]" : "Отображать кнопку - Комфортное меню [ + ]")] public bool ButtonComfortPlus = true;
				[JsonProperty(LanguageEnglish ? "Close the menu by tapping on an empty area of the screen" : "Закрыть меню нажатием на пустую область экрана")] public bool CloseUI;
				[JsonProperty(LanguageEnglish ? "Icon - Kits" : "Иконка - Наборы")] public string IconKits = "assets/icons/clothing.png";
				[JsonProperty(LanguageEnglish ? "Icon - XBuildingSkinMenu" : "Иконка - XBuildingSkinMenu")] public string IconBSkin = "assets/icons/construction.png";
				[JsonProperty(LanguageEnglish ? "Icon - Zoom" : "Иконка - Увеличение")] public string IconZoom = "assets/icons/add.png";
				[JsonProperty(LanguageEnglish ? "Material_background_0" : "Материал_фон_0")] public string BMaterial0 = "assets/icons/greyout.mat";
				[JsonProperty(LanguageEnglish ? "Color_background_0" : "Цвет_фон_0")] public string BColor0 = "0 0 0 0";
				[JsonProperty(LanguageEnglish ? "Color_background_1" : "Цвет_фон_1")] public string BColor1;
				[JsonProperty(LanguageEnglish ? "Color_background_2" : "Цвет_фон_2")] public string BColor2;
				[JsonProperty(LanguageEnglish ? "Color_background_3" : "Цвет_фон_3")] public string BColor3 = "0.1 0.1 0.1 0.975";
				[JsonProperty(LanguageEnglish ? "Color_background_4" : "Цвет_фон_4")] public string BColor4 = "0.257 0.261 0.249 1";
				[JsonProperty(LanguageEnglish ? "Active category color" : "Цвет активной категории")] public string ActiveColor;
				[JsonProperty(LanguageEnglish ? "Inactive category color" : "Цвет неактивной категории")] public string InactiveColor;
				[JsonProperty(LanguageEnglish ? "Category button color" : "Цвет кнопок категорий")] public string CategoryColor;
				[JsonProperty(LanguageEnglish ? "Settings buttons color" : "Цвет кнопок настроек")] public string SettingColor;
				[JsonProperty(LanguageEnglish ? "Button color (icons)" : "Цвет кнопок (иконки)")] public string IconColor;
				[JsonProperty(LanguageEnglish ? "Item/skin block color" : "Цвет блоков предметов/скинов")] public string BlockColor;
				[JsonProperty(LanguageEnglish ? "Color of skins/buttons block in kits" : "Цвет блоков скинов/кнопок в наборах")] public string BlockKitColor = "0.667 0.671 0.659 0.5";
				[JsonProperty(LanguageEnglish ? "Selected skin block color" : "Цвет блока выбранного скина")] public string ActiveBlockColor;
				[JsonProperty(LanguageEnglish ? "Active next/reset button color" : "Цвет активной кнопки далее/сбросить")] public string ActiveNextReloadColor;
				[JsonProperty(LanguageEnglish ? "Color of inactive next/reset button" : "Цвет неактивной кнопки далее/сбросить")] public string InactiveNextReloadColor;
				[JsonProperty(LanguageEnglish ? "Next/reset active button text color" : "Цвет текста активной кнопки далее/сбросить")] public string ActiveNextReloadColorText;				
				[JsonProperty(LanguageEnglish ? "Text color of inactive next/reset button" : "Цвет текста неактивной кнопки далее/сбросить")] public string InactiveNextReloadColorText;				
				[JsonProperty(LanguageEnglish ? "Active back button color" : "Цвет активной кнопки назад")] public string ActiveBackColor;
				[JsonProperty(LanguageEnglish ? "Back button color" : "Цвет неактивной кнопки назад")] public string InactiveBackColor;
				[JsonProperty(LanguageEnglish ? "Active back button text color" : "Цвет текста активной кнопки назад")] public string ActiveBackColorText;
				[JsonProperty(LanguageEnglish ? "Back button text color" : "Цвет текста неактивной кнопки назад")] public string InactiveBackColorText;
			}
			     
			internal class MenuSSetting 
			{ 
				[JsonProperty(LanguageEnglish ? "Enabled parameter icon" : "Иконка включенного параметра")] public string TButtonIcon;
				[JsonProperty(LanguageEnglish ? "Disabled parameter icon" : "Иконка вылюченного параметра")] public string FButtonIcon;				
				[JsonProperty(LanguageEnglish ? "Enabled parameter color" : "Цвет включенного параметра")] public string CTButton;
				[JsonProperty(LanguageEnglish ? "Disabled parameter color" : "Цвет вылюченного параметра")] public string CFButton;
			}			
			
			[JsonProperty(LanguageEnglish ? "Steam settings" : "Настройки Steam")]
			public SteamSetting Steam = new SteamSetting();
			[JsonProperty(LanguageEnglish ? "General settings" : "Общие настройки")]
			public GeneralSetting Setting = new GeneralSetting();			
			[JsonProperty(LanguageEnglish ? "Default player settings" : "Настройки игрока по умолчанию")]
			public PlayerSetting PSetting = new PlayerSetting();
			[JsonProperty(LanguageEnglish ? "Default skin kits setting" : "Настройка стандартных наборов скинов")]
			public Dictionary<string, Dictionary<string, ulong>> KitsSetting = new Dictionary<string, Dictionary<string, ulong>>();
			[JsonProperty(LanguageEnglish ? "Permissions settings. Maximum number of personal skin kits" : "Настройка разрешений. Максимальное кол-во личных наборов скинов")]
			public Dictionary<string, int> KitsCPSetting = new Dictionary<string, int>();
			[JsonProperty(LanguageEnglish ? "GUI settings" : "Настройки GUI")]
            public GUISetting GUI = new GUISetting();			
			[JsonProperty(LanguageEnglish ? "Menu settings" : "Меню настроект")]
			public MenuSSetting MenuS = new MenuSSetting();
			[JsonProperty(LanguageEnglish ? "Category settings - [ Item shortname | Default item skin ]" : "Настройка категорий - [ Шортнейм предмета | Дефолтный скин предмета ]")]
            public Dictionary<string, Dictionary<string, ulong>> Category = new Dictionary<string, Dictionary<string, ulong>>();								
			
			public static SkinConfig GetNewConfiguration()
            {
                return new SkinConfig
                {
					Steam = new SteamSetting
					{
						APIKey = "",
						IgnoredCommunitySkinsList = new HashSet<ulong>()
					},
					Setting = new GeneralSetting
					{
						UseImageLibrary = false,
						ImageAPIOption = true,
						
						//FP_TOS
						FacepunchTOS = true,
						
						UpdateSkins = false,
						UpdateSkinsFacepunch = false,
						AutoAddNewItems = true,
						RepairBench = true,
						ReskinConfig = false,
						ReskinRespawn = true,
						EnableDefaultKits = true,
						EnableCustomKits = true,
						ReissueActiveItem = true,
						ShiftUI = true,
						DeleteAdminSkins1 = true,
						DeleteAdminSkins2 = true,
						DeleteVipSkins1 = true,
						DeleteVipSkins2 = true,
						SkinCommandList = new List<string> { "skin" },
						SkinKitCommandList = new List<string> { "skinkit" },
						SkinEntityCommandList = new List<string> { "skinentity" },
						SkinItemCommandList = new List<string> { "skinitem" },
						Blacklist = new List<ulong>
						{
							1742796979,
							841106268
						},
						AdminSkins = new Dictionary<string, List<ulong>>
						{
							["rifle.ak"] = new List<ulong> { 2428514763, 2431899986, 2802928155, 2551895055, 2957212973, 2976404884 },
							["smg.mp5"] = new List<ulong> { 2468526014, 2966579723, 2590028692, 2354313222, 2558124512, 2432107615, 2351278756 },
							["metal.facemask"] = new List<ulong> { 2976455803, 2972755707, 2960187815, 2963852242, 2462021937, 1658894467, 1539950759 }
						},
						VipSkins = new Dictionary<string, List<ulong>>
						{
							["hatchet"] = new List<ulong> { 2940068053, 2891473448, 1567848320, 1414450116, 1306286667, 1277610054, 1679923378 },
							["pickaxe"] = new List<ulong> { 2940068876, 1672711156, 1624825406, 2637131316, 2837147224, 2775081117 },
							["box.wooden.large"] = new List<ulong> { 1686318599, 1651859603, 1566044873, 1547157690, 1882223552, 2068573115, 2388451898 }
						}
					},
					PSetting = new PlayerSetting
					{
						ChangeSI = true,
						ChangeSCL = true,
						ChangeSE = true,
						ChangeF = true,
						ChangeSG = false,
						ChangeSP = false,
						ChangeSGN = true,
						ChangeSC = true,
						UseSprayC = true,
						UseSoundE = true,
						Comfort = false
					},
					KitsSetting = new Dictionary<string, Dictionary<string, ulong>>
					{
						["Blackout"] = new Dictionary<string, ulong> { ["metal.facemask"] = 2105454370, ["metal.plate.torso"] = 2105505757, ["hoodie"] = 2080975449, ["pants"] = 2080977144, ["shoes.boots"] = 2090776132, ["coffeecan.helmet"] = 2120618167, ["roadsign.jacket"] = 2120615642, ["roadsign.kilt"] = 2120628865, ["roadsign.gloves"] = 2530894213, ["burlap.gloves"] = 2090790324, ["jacket"] = 2137516645, ["rifle.l96"] = 2473291137, ["rifle.ak"] = 2128372674, ["rifle.lr300"] = 2151920583, ["rifle.bolt"] = 2363806432, ["rifle.semiauto"] = 2267956984, ["smg.mp5"] = 2887642987, ["smg.thompson"] = 2393671891, ["smg.2"] = 2879438786, ["crossbow"] = 2178956071, ["bow.hunting"] = 2192571819 },						
						["Whiteout"] = new Dictionary<string, ulong> { ["metal.facemask"] = 2432948498, ["metal.plate.torso"] = 2432947351, ["hoodie"] = 2416648557, ["pants"] = 2416647256, ["shoes.boots"] = 2752873720, ["coffeecan.helmet"] = 2503956851, ["roadsign.jacket"] = 2503955663, ["roadsign.kilt"] = 2469019097, ["roadsign.gloves"] = 2469031994 },
						["Forest Raiders"] = new Dictionary<string, ulong> { ["metal.facemask"] = 2551475709, ["metal.plate.torso"] = 2551474093, ["hoodie"] = 2563940111, ["pants"] = 2563935722, ["shoes.boots"] = 2575506021, ["coffeecan.helmet"] = 2570227850, ["roadsign.jacket"] = 2570233552, ["roadsign.kilt"] = 2570237224, ["roadsign.gloves"] = 2575539874 },
						["Desert Raiders"] = new Dictionary<string, ulong> { ["metal.facemask"] = 2475428991, ["metal.plate.torso"] = 2475407123, ["hoodie"] = 2503910428, ["pants"] = 2503903214, ["shoes.boots"] = 2510093391, ["coffeecan.helmet"] = 2496517898, ["roadsign.jacket"] = 2496520042, ["roadsign.kilt"] = 2496523983, ["roadsign.gloves"] = 2510097681, ["rifle.ak"] = 2525948777, ["smg.thompson"] = 2537687634, ["rifle.semiauto"] = 2522121227 }
					},
					KitsCPSetting = new Dictionary<string, int>
					{
						["xskinmenu.kit12"] = 12,
						["xskinmenu.kit9"] = 9,
						["xskinmenu.kit6"] = 6,
						["xskinmenu.kit3"] = 3
					},
					GUI = new GUISetting
					{
						LayerUI = "Overlay",
						SkinUP = true,
						DelSkinUP = true,
						MainSkin = false,
						ButtonClear = true,
						Page = true,
						ButtonComfortPlus = true,
						CloseUI = false,
						IconKits = "assets/icons/clothing.png",
						IconBSkin = "assets/icons/construction.png",
						IconZoom = "assets/icons/add.png",
						BMaterial0 = "assets/icons/greyout.mat",
						BColor0 = "0 0 0 0",
						BColor1 = "0.517 0.521 0.509 0.95",
						BColor2 = "0.217 0.221 0.209 0.95",
						BColor3 = "0.1 0.1 0.1 0.975",
						BColor4 = "0.257 0.261 0.249 1",
						ActiveColor = "0.53 0.77 0.35 0.8",
						InactiveColor = "0 0 0 0",
						CategoryColor = "0.517 0.521 0.509 0.5",
						SettingColor = "0.517 0.521 0.509 0.5",
						IconColor = "1 1 1 0.75",
						BlockColor = "0.517 0.521 0.509 0.5",
						BlockKitColor = "0.667 0.671 0.659 0.5",
						ActiveBlockColor = "0.53 0.77 0.35 0.8",
						ActiveNextReloadColor = "0.35 0.45 0.25 1",
						InactiveNextReloadColor = "0.35 0.45 0.25 0.4",
						ActiveNextReloadColorText = "0.75 0.95 0.41 1",						
						InactiveNextReloadColorText = "0.75 0.95 0.41 0.4",
						ActiveBackColor = "0.65 0.29 0.24 1",
						InactiveBackColor = "0.65 0.29 0.24 0.4",						
						ActiveBackColorText = "0.92 0.79 0.76 1",
						InactiveBackColorText = "0.92 0.79 0.76 0.4"
					},
					MenuS = new MenuSSetting
					{
						TButtonIcon = "assets/icons/check.png",
						FButtonIcon = "assets/icons/close.png",
						CTButton = "0.53 0.77 0.35 0.8",
						CFButton = "1 0.4 0.35 0.8"
					},
					Category = _defaultCategoryAndItems.ToDictionary(k => k.Key, v => v.Value.ToDictionary(k2 => k2, v2 => 0UL))
				};
			}
        }
		
		private void OnItemAddedToContainer(ItemContainer container, Item item)
		{
			if(item == null || _ignoreItems.Contains(item)) return;
			if(container?.playerOwner != null)
			{
				BasePlayer player = container.playerOwner;
			
				if(player == null || player.IsNpc || !player.userID.IsSteamId() || player.IsSleeping()) return;
				
				if(_adminSkins.Contains(item.skin) && config.Setting.DeleteAdminSkins1 && !permission.UserHasPermission(player.UserIDString, permAdminS))
					if(!_skinIDsINT.ContainsKey(item.skin) || !(player.blueprints.steamInventory.HasItem(_skinIDsINT[item.skin]) && config.Setting.DeleteAdminSkins2))
					{
						ResetItemSkin(item);
						return;
					}
				
				if(_vipSkins.Contains(item.skin) && config.Setting.DeleteVipSkins1 && !permission.UserHasPermission(player.UserIDString, permVipS))
					if(!_skinIDsINT.ContainsKey(item.skin) || !(player.blueprints.steamInventory.HasItem(_skinIDsINT[item.skin]) && config.Setting.DeleteVipSkins2))
					{
						ResetItemSkin(item);
						return;
					}
				
				if(_removeATC.Contains(player.userID)) return;
				if(!StoredData.TryGetValue(player.userID, out Data data) || !data.ChangeSG) return;

				string shortname = item.info.shortname;

				if(!data.Skins.ContainsKey(shortname)) return;
				if(config.Setting.ReskinConfig && !_items.ContainsKey(shortname)) return;
				if(!permission.UserHasPermission(player.UserIDString, permGive)) return;

				SetSkinCraftGive(player, item, true);
			}
		}
		
		private void AllSkinsRequest()
		{
			HashSet<ulong> allSkins = new HashSet<ulong>();
			
			foreach(KeyValuePair<string, List<ulong>> item in StoredDataSkins)
			{
				if(item.Key == "battery.small") continue;
				
				allSkins.UnionWith(item.Value);
			}
			
			allSkins.UnionWith(_adminAndVipSkins);
			
			foreach(Dictionary<string, ulong> item in config.KitsSetting.Values)
				foreach(ulong itemSkinID in item.Values)
					allSkins.Add(itemSkinID);
			
			foreach(Dictionary<string, ulong> item in config.Category.Values)
				foreach(ulong itemSkinID in item.Values)
					allSkins.Add(itemSkinID);
			
			allSkins.RemoveWhere(skin => skin < 20000UL || _allFPSkins.Contains(skin));
			
			if(allSkins.Count != 0)
			{
				int totalRequests = (int)Math.Ceiling((double)allSkins.Count / 100);
				
				PrintWarning(LanguageEnglish
					? $"{allSkins.Count} community skins were found in the plugin files! Plugin will make {totalRequests} requests to the SteamAPI!"
					: $"Найдено {allSkins.Count} комьюнити скинов в файлах плагина! Плагин выполнит {totalRequests} запросов к SteamAPI!");
				
				BatchWEBRequest(allSkins.ToList(), 0, new HashSet<ulong>(), 1, totalRequests);
			}
		}
		
		[ConsoleCommand("xbuildingskinmenu_open")]
		private void ccmdXBuildingSkinMenuOpen(ConsoleSystem.Arg args)
		{
			BasePlayer player = args.Player();
			
			if(player != null)
				XBuildingSkinMenu?.Call("cmdOpenGUIB", player, null, null);
		}
		
		private Dictionary<ulong, int> _skinIDsINT = new Dictionary<ulong, int>();
		
		private void OnItemCraftFinished(ItemCraftTask task, Item item, ItemCrafter crafter)
		{
			if(task.skinID == 0)
			{
				BasePlayer player = crafter.owner;

				if(player == null || !StoredData.TryGetValue(player.userID, out Data data) || data.ChangeSG || !data.ChangeSC) return;

				string shortname = item.info.shortname;

				if(!data.Skins.TryGetValue(shortname, out ulong skin)) return;
				if(!permission.UserHasPermission(player.UserIDString, permCraft)) return;

				if(ersK.TryGetValue(shortname, out Dictionary<ulong, string> redirects) && redirects.ContainsKey(skin))
					NextTick(() => SetSkinCraftGive(player, item, true));
				else
					SetSkinCraftGive(player, item);
			}
		}
		
		private void ManagementSkins()
		{
			List<ulong> skinsNameKeys = StoredDataSkinsName.Keys.ToList();
			
			foreach(ulong skinNameKey in skinsNameKeys)
				StoredDataSkinsName[skinNameKey] = GetCleanSkinName(StoredDataSkinsName[skinNameKey]);
			
			SaveDataSkinsName();
			
			timer.Once(10, () => AllSkinsRequest());
		}
		private const string permVipS = "xskinmenu.vipskins";
		
				
		private void CanTakeKit(BasePlayer player) => _removeATC.Add(player.userID);
		private const string permKitsC = "xskinmenu.customkits";
		
				
		private Dictionary<string, ulong> API_GetSkinsPlayer(ulong userID) => StoredData[userID].Skins;
		private HashSet<ulong> _vipSkins = new HashSet<ulong>();
		private void CanSetAutoKit(BasePlayer player) => _removeATC.Add(player.userID);
		
		private void KitInfoGUI(BasePlayer player, string category, string kitname)
		{
			Dictionary<string, ulong> skins = category == "default" && config.Setting.EnableDefaultKits && config.KitsSetting.ContainsKey(kitname) && permission.UserHasPermission(player.UserIDString, permKitsD) ? config.KitsSetting[kitname] : category == "custom" && config.Setting.EnableCustomKits && StoredData[player.userID].Kits.ContainsKey(kitname) && permission.UserHasPermission(player.UserIDString, permKitsC) ? StoredData[player.userID].Kits[kitname] : new Dictionary<string, ulong>();
			
			CuiElementContainer container = new CuiElementContainer();
			
			bool adminSkinPerm = !permission.UserHasPermission(player.UserIDString, permAdminS), vipSkinPerm = !permission.UserHasPermission(player.UserIDString, permVipS);
			int x = 0, y = 0;
			
			container.Add(new CuiPanel
			{
				RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
				Image = { Color = config.GUI.BColor4 }
			}, ".KitsGUI", ".KitInfo", ".KitInfo");
			
			container.Add(new CuiButton
			{
				RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = "-14.125 10", OffsetMax = "14.125 38.25" },
				Button = { Color = config.GUI.IconColor, Sprite = "assets/icons/close.png", Close = ".KitInfo" },
				Text = { Text = "" }
			}, ".KitInfo");
			
			foreach(var skin in skins)
			{
				bool isAdminSkin = _adminSkins.Contains(skin.Value), isVipSkin = _vipSkins.Contains(skin.Value), adminOrVip = isAdminSkin && adminSkinPerm || isVipSkin && vipSkinPerm;
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = $"{-497.5 + (x * 71.42)} {131.33 - (y * 71.42)}", OffsetMax = $"{-431.08 + (x * 71.42)} {197.75 - (y * 71.42)}" },
					Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
				}, ".KitInfo", ".Skin");
				
					container.Add(new CuiElement
					{
						Parent = ".Skin",
						Components =
						{
							new CuiImageComponent { ItemId = _itemsId[skin.Key], SkinId = skin.Value, Color = adminOrVip ? "1 1 1 0.2" : "1 1 1 1" },
							new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "2.5 2.5", OffsetMax = "-2.5 -2.5" }
						}
					});		
					
				if(isAdminSkin)
				    container.Add(new CuiPanel
                    {
                        RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2 -10", OffsetMax = "10 -2" },
                        Image = { Color = "0.9 0 0 1", Sprite = "assets/icons/circle_closed.png" },
                    }, ".Skin");
				else if(isVipSkin)
				    container.Add(new CuiPanel
                    {
                        RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2 -10", OffsetMax = "10 -2" },
                        Image = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/circle_closed.png" },
                    }, ".Skin");
				
				x++;
				
				if(x == 14)
				{
					x = 0;
					y++;
					
					if(y == 5)
						break;
				}
			}
			
			CuiHelper.AddUi(player, container);
		}
		
		
        public class CollectionQueryResponse
        {
            public CollectionResponse response;
        }
		private Dictionary<ulong, bool> StoredDataFriends = new Dictionary<ulong, bool>();
		
				
		private int GetMaxCountKits(BasePlayer player)
		{
			foreach(var perm in config.KitsCPSetting)
				if(permission.UserHasPermission(player.UserIDString, perm.Key))
					return perm.Value;
			
			return 0;
		}
		
		private ulong _imageID = 1110;
		   		 		  						  	   		   		 		  		 			   					  	 	 
        public class CollectionResponse
        {
            public int resultcount;
            public CollectionDetails[] collectiondetails;
        }
		
		private void SetItemGUI(BasePlayer player, string item, int Page = 0, bool isentity = false, string search = "")
		{
			bool isAct = config.Setting.ReissueActiveItem && config.Setting.ShiftUI;
			CuiElementContainer container = new CuiElementContainer();
			
			container.Add(new CuiElement
			{
				Parent = "Overlay",
				Name = ".SetItem",
				DestroyUi = ".SetItem",
				Components =
				{
					new CuiImageComponent { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" },
					new CuiRectTransformComponent { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = isentity ? "-389.75 0" : $"{(isAct ? -635 : -399.5)} 90", OffsetMax = isentity ? "390.25 226.75" : $"{(isAct ? 145 : 380.5)} 316.75" },
					new CuiNeedsCursorComponent {},
					new CuiNeedsKeyboardComponent {}
				}
			});
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "5 5", OffsetMax = "-5 -5" },
                Image = { Color = config.GUI.BColor2 }
            }, ".SetItem", ".SetItemB");
			
			int x = 0, y = 0;
			ulong s = StoredData[player.userID].Skins[item];
			int itemid = _itemsId[item];
			
			HashSet<ulong> list_skins = s == 0 ? new HashSet<ulong>{ 0 } : new HashSet<ulong>{ 0, s };
			
			if(config.Setting.AdminSkins.ContainsKey(item) && permission.UserHasPermission(player.UserIDString, permAdminS))
				list_skins.UnionWith(config.Setting.AdminSkins[item]);
			if(config.Setting.VipSkins.ContainsKey(item) && permission.UserHasPermission(player.UserIDString, permVipS))
				list_skins.UnionWith(config.Setting.VipSkins[item]);
			
			list_skins.UnionWith(StoredDataSkins[item]);
			
			if(!string.IsNullOrEmpty(search))
			{
				if(ulong.TryParse(search, out ulong searchSkinID))
					list_skins = list_skins.Where(skinID => skinID == searchSkinID).ToHashSet();
				else
					list_skins = list_skins.Where(skinID => StoredDataSkinsName.TryGetValue(skinID, out string name) && name.Contains(search, StringComparison.OrdinalIgnoreCase)).ToHashSet();
			}
			
			foreach(ulong skin in list_skins.Skip(Page * 18))
			{
			    container.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = $"{-380 + (x * 85)} {23.375 - (y * 85)}", OffsetMax = $"{-300 + (x * 85)} {103.375 - (y * 85)}" },
                    Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
                }, ".SetItemB", ".SkinB");
				
					container.Add(new CuiElement
					{
						Parent = ".SkinB",
						Components =
						{
							GetImageComponent(itemid, skin),
							new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "7.5 2.5", OffsetMax = "-7.5 -12.5" }
						}
					});		
				
				container.Add(new CuiButton
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
                    Button = { Color = "0 0 0 0", Command = isentity ? $"skin_c setskinentity {item} {skin}" : $"skin_c setskinitem {item} {skin}" },
                    Text = { Text = "" }
                }, ".SkinB");
				
				if(StoredDataSkinsName.ContainsKey(skin))
					container.Add(new CuiLabel
					{
						RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "1.5 -12", OffsetMax = "-1.5 -2" },
						Text = { Text = StoredDataSkinsName[skin], Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 8, Color = "0.85 0.85 0.85 1" }
					}, ".SkinB");
					
				container.Add(new CuiButton
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "0 0", OffsetMin = "2.5 2.5", OffsetMax = "15 15" },
                    Button = { Color = "1 1 1 0.75060739", Sprite = config.GUI.IconZoom, Command = $"skin_c zoomskin {itemid} {skin} true" },
                    Text = { Text = "" }
                }, ".SkinB");
				
				if(s == skin)
				    container.Add(new CuiPanel
                    {
                        RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-15 2.5", OffsetMax = "-2.5 15" },
                        Image = { Color = config.GUI.ActiveBlockColor, Sprite = "assets/icons/circle_closed.png" },
                    }, ".SkinB");
				
				if(_adminSkins.Contains(skin))
				    container.Add(new CuiPanel
                    {
                        RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2.5 -22", OffsetMax = "10 -14.5" },
                        Image = { Color = "0.9 0 0 1", Sprite = "assets/icons/circle_closed.png" },
                    }, ".SkinB");
				else if(_vipSkins.Contains(skin))
				    container.Add(new CuiPanel
                    {
                        RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2.5 -22", OffsetMax = "10 -14.5" },
                        Image = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/circle_closed.png" },
                    }, ".SkinB");
				
				x++;
				
				if(x == 9)
				{
					x = 0;
					y++;
					
					if(y == 2)
						break;
				}
			}
			
			bool back = Page != 0;
			bool next = list_skins.Count > ((Page + 1) * 18);
			
			container.Add(new CuiButton
            {    
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-286.75 5" : "-231.75 5", OffsetMax = config.GUI.Page ? "-196.75 31.75" : "-141.75 31.75" },
                Button = { Color = back ? config.GUI.ActiveBackColor : config.GUI.InactiveBackColor, Command = back ? $"page.xskinmenu skinitem {item} {Page - 1} {isentity} {search}" : "" },
                Text = { Text = lang.GetMessage("BACK", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = back ? config.GUI.ActiveBackColorText : config.GUI.InactiveBackColorText }
            }, ".SetItemB");
			
			if(config.GUI.Page)
				container.Add(new CuiLabel
				{
					RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-194.25 5", OffsetMax = "-139.25 31.75" },
					Text = { Text = $"{Page + 1}/{Math.Ceiling((double)list_skins.Count / 18)}", Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 15, Color = "0.75 0.75 0.75 0.9" }
				}, ".SetItemB");
			
			container.Add(new CuiButton
            {
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-136.75 5", OffsetMax = "-46.75 31.75" },
                Button = { Color = next ? config.GUI.ActiveNextReloadColor : config.GUI.InactiveNextReloadColor, Command = next ? $"page.xskinmenu skinitem {item} {Page + 1} {isentity} {search}" : "" },
                Text = { Text = lang.GetMessage("NEXT", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = next ? config.GUI.ActiveNextReloadColorText : config.GUI.InactiveNextReloadColorText }
            }, ".SetItemB");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-296.75 0" : "-241.75 0", OffsetMax = config.GUI.Page ? "-291.75 36.75" : "-236.75 36.75" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".SetItemB");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMin = "0 36.75", OffsetMax = "0 41.75" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".SetItemB");
			
			container.Add(new CuiButton
            {
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-31.75 5", OffsetMax = "-5 31.75" },
                Button = { Color = config.GUI.IconColor, Sprite = "assets/icons/close.png", Close = ".SetItem" },
                Text = { Text = "" }
            }, ".SetItemB");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-41.75 0", OffsetMax = "-36.75 41.75" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".SetItemB");
			
			container.Add(new CuiPanel
			{
				RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-442 5" : "-386.5 5", OffsetMax = config.GUI.Page ? "-302 31.75" : "-246.5 31.75" },
				Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
			}, ".SetItemB", ".Input");
			
			container.Add(new CuiElement
            {
                Parent = ".Input",
                Components =
                {
                    new CuiInputFieldComponent { Text = string.IsNullOrEmpty(search) ? lang.GetMessage("SKIN_SEARCH", this, player.UserIDString) : search, Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 10, Command = $"skin_c searchskinmini {item} {isentity}", CharsLimit = 30, NeedsKeyboard = true, Color = "0.8 0.8 0.8 1" },
                    new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" }
                }
            });
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-452 0" : "-396.5 0", OffsetMax = config.GUI.Page ? "-447 36.75" : "-391.5 36.75" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".SetItemB");
			
			CuiHelper.AddUi(player, container);
		}
		private Dictionary<BasePlayer, DateTime> CooldownsAdd = new Dictionary<BasePlayer, DateTime>();
		
		private const string permUse = "xskinmenu.use";
		
		private void RemoveATC(ulong userID) => _removeATC.Remove(userID);
		
		private void ResetItemSkin(Item item)
		{
			item.skin = 0;
			
			BaseEntity held = item.GetHeldEntity();
			if(held != null)
			{
				held.skinID = 0;
				held.SendNetworkUpdate(BasePlayer.NetworkQueue.Update);
			}
			
			BaseEntity world = item.GetWorldEntity();
			if(world != null)
			{
				world.skinID = 0;
				world.SendNetworkUpdate(BasePlayer.NetworkQueue.Update);
			}
			
			item.MarkDirty();
		}
		
		private readonly Dictionary<string, WallpaperSide> _blocksWallpapersShortnames = new Dictionary<string, WallpaperSide>
		{
			["wall"] = new WallpaperSide("wallpaper.wall", "wallpaper.wall"),
			["wall.window"] = new WallpaperSide("wallpaper.wall", "wallpaper.wall"),
			["wall.doorway"] = new WallpaperSide("wallpaper.wall", "wallpaper.wall"),
			["wall.half"] = new WallpaperSide("wallpaper.wall", "wallpaper.wall"),
			["wall.low"] = new WallpaperSide("wallpaper.wall", "wallpaper.wall"),
			["foundation"] = new WallpaperSide("wallpaper.flooring", "wallpaper.flooring"),
			["foundation.triangle"] = new WallpaperSide("wallpaper.flooring", "wallpaper.flooring"),
			["floor"] = new WallpaperSide("wallpaper.ceiling", "wallpaper.flooring"),
			["floor.triangle"] = new WallpaperSide("wallpaper.ceiling", "wallpaper.flooring"),
			["roof"] = new WallpaperSide("wallpaper.ceiling", "wallpaper.ceiling"),
			["roof.triangle"] = new WallpaperSide("wallpaper.ceiling", "wallpaper.ceiling")
		};
		
				 
				
		private SkinConfig config;
		
		private void SetSkinItem(BasePlayer player, string item, ulong skin)
		{
			using(PooledList<Item> pooledList = Pool.Get<PooledList<Item>>())
			{
				if(ersK.ContainsKey(item))
				{
					foreach(var itm in ersK[item])
					{
						if(itm.Key == skin) continue;
						
						pooledList.Clear();
						player.inventory.FindItemsByItemID(pooledList, ItemManager.FindItemDefinition(itm.Value).itemid);
						
						foreach(var i in pooledList)
						{
							if(Blacklisted(i.skin)) continue;
							
							SSI(player, i, skin, item);
						}
					}
					
					pooledList.Clear();
					player.inventory.FindItemsByItemID(pooledList, ItemManager.FindItemDefinition(item).itemid);
					
					foreach(var i in pooledList)
					{
						if(i.skin == skin || Blacklisted(i.skin)) continue;
						
						SSI(player, i, skin, item);
					}
				}
				else
				{
					player.inventory.FindItemsByItemID(pooledList, ItemManager.FindItemDefinition(item).itemid);
					
					foreach(var i in pooledList)
					{
						if(i.skin == skin || Blacklisted(i.skin)) continue;
						
						SSI(player, i, skin);
					}
				}
			}
		}
		
		private object OnEntityReskin(BaseEntity entity, ItemSkinDirectory.Skin skin, BasePlayer player)
		{
			if(Blacklisted(entity.skinID))
			{
				EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
				return false;
			}
			
			return null;
		}
		private const string permSetting = "xskinmenu.setting";
		
		private void AddToBlacklist(List<ulong> skinIDs, string pluginName = "Unknown")
		{
			if(skinIDs != null)
			{
				int x = 0;
				string msg = LanguageEnglish ? $"The [ {pluginName} ] plugin has blacklisted the skins -" : $"Плагин [ {pluginName} ] добавил в черный список скины -";
				
				foreach(ulong skinID in skinIDs)
				{
					if(skinID != 0 && !Blacklisted(skinID))
					{
						config.Setting.Blacklist.Add(skinID);
						_blacklist.Add(skinID);
						msg += $" {skinID}";
						
						x++;
					}
				}
					
				if(x > 0)
				{
					PrintWarning(msg);
					
					SaveConfig();
				}
			}
		}
		   		 		  						  	   		   		 		  		 			   					  	 	 
        public class CollectionChild
        {
            public string publishedfileid;
            public int filetype;
        }
		private void CanGiveKit(BasePlayer player) => _removeATC.Add(player.userID);
		   		 		  						  	   		   		 		  		 			   					  	 	 
        public class CollectionDetails
        {
            public int result;
            public CollectionChild[] children;
        }
		
		[ConsoleCommand("xskin2")]
		private void ccmdAdmin2(ConsoleSystem.Arg args)
		{
			if(args?.Args != null && args.Args.Length > 1)
				if(args.Player() == null || permission.UserHasPermission(args.Player().UserIDString, permAdmin))
					switch(args.GetString(0))
					{
						case "add2":
						{
							AddRemoveSkins(args.Args.Select(sv => sv.ToString()).ToArray(), true);
							
							break;
						}
						case "remove2":
						{
							AddRemoveSkins(args.Args.Select(sv => sv.ToString()).ToArray(), false);
							
							break;
						}
					}
		}
		
		private void OnPlayerInput(BasePlayer player, InputState input)
		{
			if(!input.WasJustPressed(BUTTON.FIRE_SECONDARY) || !StoredData.TryGetValue(player.userID, out Data data) || !data.UseSprayC) return;
			
			if(permission.UserHasPermission(player.UserIDString, permSprayC))
			{
				Item item = player.GetActiveItem();
				
				if(item != null && item.info.shortname == "spraycan")
					EntitySkin(player);
			}
		}
		
		private const string _partURL = "?imw=150&imh=150&ima=fit&impolicy=Letterbox";
		
		private IEnumerator DownloadCommunitySkinsImage()
		{
			PrintWarning(LanguageEnglish ? "Start downloading community skin images!" : "Начало скачивания изображений комьюнити скинов!");
			
			foreach(KeyValuePair<ulong, string> skin in _allCommunitySkinsURL)
			{
				CheckSkinIDImageLibrary(skin.Key, skin.Value);
				
				yield return CoroutineEx.waitForSeconds(0.4f);
			}
			
			PrintWarning(LanguageEnglish ? "Download complete! Image processing is now being handled by ImageLibrary!" : "Скачивание завершено! Обработка изображений на стороне ImageLibrary!");
			
			_coroutineDownloadCommunitySkinsImage = null;
			yield return 0;
		}
		   		 		  						  	   		   		 		  		 			   					  	 	 
        public class PublishedFileDetails
        {
            public string publishedfileid;
			public int result;
			public int consumer_app_id;
			public string preview_url;
			public string title;
            public Tag[] tags;
		   		 		  						  	   		   		 		  		 			   					  	 	 
            public class Tag
            {
                public string tag;
            }
        }
		
		private HashSet<ulong> _adminUiFD = new HashSet<ulong>();
		
		private ICuiComponent GetImageComponent(int itemid, ulong skin)
		{
			if(config.Setting.UseImageLibrary && skin > 20000UL && !_allFPSkins.Contains(skin) && !config.Steam.IgnoredCommunitySkinsList.Contains(skin))
			{
				if(!_imageDBNames.TryGetValue(skin, out string imageDBName))
				{
					string imageName = skin.ToString();
					
					if(ImageLibrary.Call<bool>("HasImage", imageName, _imageID))
					{
						imageDBName = ImageLibrary.Call<string>("GetImage", imageName, _imageID);
						_imageDBNames[skin] = imageDBName;
					}
				}
				
				return string.IsNullOrEmpty(imageDBName) ? new CuiImageComponent { ItemId = itemid } : new CuiRawImageComponent { Png = imageDBName };
			}
			
			return new CuiImageComponent { ItemId = itemid, SkinId = skin };
		}
		private Dictionary<ulong, List<ulong>> _workshopCollections = new Dictionary<ulong, List<ulong>>();
		
				
				
	    internal class Data
		{
			public bool ChangeSI;
			public bool ChangeSCL;
			public bool ChangeSE;
			public bool ChangeSG;
			public bool ChangeSP;
			public bool ChangeSGN;
			public bool ChangeSC;
			public bool UseSprayC;
			public bool UseSoundE;
			public bool Comfort;
			public bool ComfortP;
			public Dictionary<string, Dictionary<string, ulong>> Kits = new Dictionary<string, Dictionary<string, ulong>>();
			public Dictionary<string, ulong> Skins = new Dictionary<string, ulong>();
		}
		
		private List<ulong> _defaultAdminVipSkins = new List<ulong> { 2940068053, 28973448, 15678320, 1414450116, 1306286667, 1277610054, 1679923378, 16318599, 1651859603, 1566044873, 1547157690, 18823552, 2068573115, 2377581898, 294006876, 1672711156, 1624825406, 26331316, 2837147224, 27751117 };
		
				
				
		[PluginReference] private Plugin XBuildingSkinMenu, ImageLibrary;
		
		private Coroutine _coroutineDownloadCommunitySkinsImage;
		
		private string GetCleanSkinName(string skinName)
		{
			if(string.IsNullOrEmpty(skinName)) 
				return "???";
			
			return _skinNameFilter.Replace(skinName, "").Trim();
		}
		
		private void OnPlayerConnected(BasePlayer player)
		{
			if(player.IsReceivingSnapshot)
            {
                NextTick(() => OnPlayerConnected(player));
                return;
            }		
			
			LoadData(player);
			ResetPlayerSkins(player);
		}  
		
		private HashSet<ulong> _removeATC = new HashSet<ulong>();
		
		private readonly HashSet<Item> _ignoreItems = new HashSet<Item>();
		
		private Dictionary<ulong, Coroutine> _coroutineListCollections = new Dictionary<ulong, Coroutine>();
		
		private void CleanDictionary(Dictionary<string, List<ulong>> dict, HashSet<ulong> invalidSkins)
		{
			foreach(KeyValuePair<string, List<ulong>> item in dict)
				item.Value.RemoveAll(skin => invalidSkins.Contains(skin));
		}
		
		private Dictionary<ulong, string> _imageDBNames = new Dictionary<ulong, string>();
		
		private void SkinsWorkshopRequest(ulong collectionID)
		{
			string details = $"?key={config.Steam.APIKey}&itemcount={_workshopSkins[collectionID].Count}";
			
			for(int i = 0; i < _workshopSkins[collectionID].Count; i++)
				details += $"&publishedfileids[{i}]={_workshopSkins[collectionID][i]}";
			
			webrequest.Enqueue("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/", details, (code, response) => _coroutineListCollections[collectionID] = ServerMgr.Instance.StartCoroutine(SkinsRequest(code, response, collectionID)), this, RequestMethod.POST);
		}
		
				
		private static readonly Regex _skinNameFilter = new Regex(@"[\x00-\x1F\x7F\\""\uD800-\uDBFF\uDC00-\uDFFF]", RegexOptions.Compiled);
		
		//FP_TOS
		private HashSet<ulong> _allFPSkins = new HashSet<ulong>();
		
		private void OnItemOwnershipSharesChange(Item oldItem, Item newItem)
		{
			if(oldItem.info.shortname == "lr300" || oldItem.info.shortname == "rifleAK" || newItem.info.shortname == "lr300" || newItem.info.shortname == "rifleAK")
				return;
			
			if(oldItem.ownershipShares != null)
			{
				newItem.ownershipShares = oldItem.ownershipShares;
				oldItem.ownershipShares = null;
			}
		}
		
		private void SSI(BasePlayer player, Item item, ulong skin, string itm = "", bool isgive = false, bool isActiveItem = false)
		{
			if(item.info.shortname == itm && item.skin == skin) return;
			
			ItemContainer parentContaine = item.parent;
			
			if(isActiveItem || (ersK.ContainsKey(itm) && ersK[itm].ContainsKey(skin)) || (ersK.ContainsKey(itm) && itm != item.info.shortname))
			{
				if(item.amount <= 0) return;
				if(itm == "largebackpack" && item.contents.itemList.Count != 0) return;
				
				int ammoCount = 0, ammoChainsawCount = 0;
				ItemDefinition ammoType = null;
				
				if(item.GetHeldEntity() != null)
				{
					if(itm == "chainsaw")
					{
						Chainsaw chainsaw = item.GetHeldEntity() as Chainsaw;
						
						ammoChainsawCount = chainsaw.ammo;
					}
					else
					{
						BaseProjectile heldEntity = item.GetHeldEntity() as BaseProjectile;
						
						if(heldEntity != null && heldEntity.primaryMagazine != null)
						{
							ammoCount = heldEntity.primaryMagazine.contents;
							ammoType = heldEntity.primaryMagazine.ammoType;
						}
					}
				}
				
				//Item newitem = ItemManager.CreateByName(ersK[itm].ContainsKey(skin) ? ersK[itm][skin] : itm, item.amount, ersK[itm].ContainsKey(skin) ? 0 : skin);
				Item newitem = ItemManager.CreateByName(itm, item.amount, skin);
				
				newitem.condition = item.condition;
				newitem.maxCondition = item.maxCondition;
				newitem.name = item.name;
				newitem.text = item.text;
				
				if(item.ownershipShares != null)
				{
					newitem.ownershipShares = item.ownershipShares;
					item.ownershipShares = null;
				}
				
				if(item.contents != null)
				{
					if(newitem.contents == null)
					{
						ProtoBuf.ItemContainer savedContainer = item.contents.Save();
						
						newitem.contents = Pool.Get<ItemContainer>();
						
						if(newitem.isServer)
							newitem.contents.ServerInitialize(newitem, savedContainer.slots);
						
						newitem.contents.Load(savedContainer);
					}
					else
						foreach(var module in item.contents.itemList)
						{
							Item content = ItemManager.CreateByName(module.info.shortname, module.amount, module.skin);
							content.condition = module.condition;
							content.maxCondition = module.maxCondition;
							content.name = module.name;
							content.text = module.text;
							
							if(module.ownershipShares != null)
							{
								content.ownershipShares = module.ownershipShares;
								module.ownershipShares = null;
							}
							
							content.MoveToContainer(newitem.contents);
						}
				}
				
				if(itm == "chainsaw")
				{
					if(newitem.GetHeldEntity() != null)
					{
						Chainsaw newChainsaw = newitem.GetHeldEntity() as Chainsaw;
						
						newChainsaw.ammo = ammoChainsawCount;
					}
				}
				else
				{
					if(ammoType != null && newitem.GetHeldEntity() != null)
					{
						BaseProjectile baseProjectile = newitem.GetHeldEntity() as BaseProjectile;
						
						if(baseProjectile != null)
						{
							if(baseProjectile.primaryMagazine != null)
							{
								baseProjectile.SetAmmoCount(ammoCount);
								baseProjectile.primaryMagazine.ammoType = ammoType;
							}
							
							baseProjectile.ForceModsChanged();
						}
					}
				}
				
				int slot = item.position;
				
				item.Remove();
				item.RemoveFromWorld();
				item.RemoveFromContainer();
				
				if(parentContaine != null)
				{
					_ignoreItems.Add(newitem);
					
					if(!newitem.MoveToContainer(parentContaine, isgive && newitem.info.stackable > 1 ? -1 : slot))
						newitem.Drop(player.inventory.containerMain.dropPosition, player.inventory.containerMain.dropVelocity, new Quaternion());
					
					_ignoreItems.Remove(newitem);
				}
				else
					newitem.Drop(player.inventory.containerMain.dropPosition, player.inventory.containerMain.dropVelocity, new Quaternion());
			}
			else
			{
                item.skin = skin;
				
                BaseEntity held = item.GetHeldEntity();
                if(held != null)
                {
                    held.skinID = skin;
                    held.SendNetworkUpdate(BasePlayer.NetworkQueue.Update);
                }
				
				BaseEntity world = item.GetWorldEntity();
                if(world != null)
                {
                    world.skinID = skin;
                    world.SendNetworkUpdate(BasePlayer.NetworkQueue.Update);
                }
				
				item.MarkDirty();
				
				if(isgive && item.info.stackable > 1)
				{
					if(parentContaine != null)
					{
						item.RemoveFromContainer();
						
						_ignoreItems.Add(item);
						
						if(!item.MoveToContainer(parentContaine))
							item.Drop(player.inventory.containerMain.dropPosition, player.inventory.containerMain.dropVelocity, new Quaternion());
						
						_ignoreItems.Remove(item);
					}
					else
						item.Drop(player.inventory.containerMain.dropPosition, player.inventory.containerMain.dropVelocity, new Quaternion());
				}
			}
		}
		
		private void CategoryGUI(BasePlayer player, int page = 0)
		{
			if(StoredData[player.userID].Comfort) CuiHelper.DestroyUi(player, ".SkinGUI");
			CuiHelper.DestroyUi(player, ".KitsGUI");
            CuiElementContainer container = new CuiElementContainer();
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-497.5 187.5", OffsetMax = "497.5 222.5" },
                Image = { Color = "0 0 0 0" }
            }, ".SGUI", ".SkinBUTTON", ".SkinBUTTON");
			
			int x = 0, count = config.Category.Count; 
			
			foreach(var category in config.Category)
			{
				double offset = -(81 * count--) + -(2.5 * count--);
		   		 		  						  	   		   		 		  		 			   					  	 	 
				container.Add(new CuiButton
                {
                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = $"{offset} -17.5", OffsetMax = $"{offset + 162} 17.5" },
                    Button = { Color = config.GUI.CategoryColor, Material = "assets/icons/greyout.mat", Command = $"skin_c category {category.Key} {x}" },
                    Text = { Text = lang.GetMessage(category.Key, this, player.UserIDString), Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 18, Color = "0.75060739 0.75060739 0.75060739 1" }
                }, ".SkinBUTTON", ".BUTTON");
 
			    container.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMax = "0 1.5" },
                    Image = { Color = page == x ? config.GUI.ActiveColor : config.GUI.InactiveColor, Material = "assets/icons/greyout.mat" }
                }, ".BUTTON");
				
				x++;
			}
			
			CuiHelper.AddUi(player, container);
		}
		
	    private void OnPlayerDisconnected(BasePlayer player)
		{
			if(StoredData.ContainsKey(player.userID)) 
			{   
				SaveData(player);
				StoredData.Remove(player.userID);
			}			
			  
			if(Cooldowns.ContainsKey(player))
				Cooldowns.Remove(player);  
		}
		
				
				
		private void cmdOpenGUI(BasePlayer player, string command, string[] args) 
		{
			if(!permission.UserHasPermission(player.UserIDString, permUse))
				SendReply(player, lang.GetMessage("NOPERM", this, player.UserIDString));
			else
			    GUI(player);
		}
		
		private void CustomKitsGUI(BasePlayer player, int Page = 0)
		{
			bool edk = config.Setting.EnableDefaultKits;
			CuiElementContainer container = new CuiElementContainer();
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = edk ? "0.5 0" : "0 0", AnchorMax = "1 1", OffsetMin = edk ? "2.5 0" : "0 0", OffsetMax = "0 0" },
                Image = { Color = "0 0 0 0" }
            }, ".KitsGUI", ".CustomKitsGUI", ".CustomKitsGUI");
			
			container.Add(new CuiLabel
			{
				RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "0 -40.25", OffsetMax = "0 0" },
				Text = { Text = lang.GetMessage("DESC_CUSTOM_KITS", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 18, Font = "robotocondensed-regular.ttf", Color = "0.8 0.8 0.8 0.75" }
			}, ".CustomKitsGUI");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "0 -45.25", OffsetMax = "0 -40.25" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".CustomKitsGUI");
			
			if(permission.UserHasPermission(player.UserIDString, permKitsC))
			{
				if(Page == 0)
				{
					container.Add(new CuiPanel
					{
						RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = edk ? "-245 -7.5" : "-492.5 -7.5", OffsetMax = edk ? "-85 152.5" : "-332.5 152.5" },
						Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
					}, ".CustomKitsGUI", ".CKitAdd");
					
					container.Add(new CuiButton
					{    
						RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "35 42.5", OffsetMax = "-35 -27.5" },
						Button = { Color = config.GUI.IconColor, Sprite = "assets/icons/add.png", Command = "skin_c createkitui" },
						Text = { Text = "" }
					}, ".CKitAdd");
					
					container.Add(new CuiLabel
					{
						RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMax = "0 20" },
						Text = { Text = string.Format(lang.GetMessage("CUSTOM_KITS_AMOUNT", this, player.UserIDString), StoredData[player.userID].Kits.Count, GetMaxCountKits(player)), Align = TextAnchor.MiddleCenter, FontSize = 12, Font = "robotocondensed-regular.ttf", Color = "0.8 0.8 0.8 0.75060739" }
					}, ".CKitAdd");
				}
				
				int x = Page == 0 ? 1 : 0, y = 0, count = edk ? 6 : 12;
				
				foreach(var kit in StoredData[player.userID].Kits.Skip((Page * count) - 1))
				{
					container.Add(new CuiPanel
					{
						RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = $"{(edk ? -245 : -492.5) + (x * 165)} {-7.5 - (y * 165)}", OffsetMax = $"{( edk ? -85 : -332.5) + (x * 165)} {152.5 - (y * 165)}" },
						Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
					}, ".CustomKitsGUI", ".CKit");
					
					container.Add(new CuiLabel
					{
						RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "5 -20.5", OffsetMax = $"-25.5 -5" },
						Text = { Text = kit.Key.Replace(@"\", ""), Align = TextAnchor.MiddleLeft, FontSize = 11, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
					}, ".CKit");
					
				    container.Add(new CuiButton
                    {
                        RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-19.5 -19.5", OffsetMax = $"-6 -6" },
                        Button = { Color = "1 1 1 0.75", Sprite = "assets/icons/clear.png", Command = $"skin_c clearkit '{kit.Key}' {Page}" },
                        Text = { Text = "" }
                    }, ".CKit");
					
					int xx = 0, yy = 0;
					
					foreach(var skin in kit.Value.Take(kit.Value.Count > 6 ? 5 : 6))
					{
						container.Add(new CuiPanel
						{
							RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = $"{-75 + (xx * 51.75)} {7 - (yy * 52.5)}", OffsetMax = $"{-28.25 + (xx * 51.75)} {54.5 - (yy * 52.5)}" },
							Image = { Color = config.GUI.BlockKitColor }
						}, ".CKit", ".Skin");
						
							container.Add(new CuiElement
							{
								Parent = ".Skin",
								Components =
								{
									new CuiImageComponent { ItemId = _itemsId[skin.Key], SkinId = skin.Value },
									new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "2.5 2.5", OffsetMax = "-2.5 -2.5" }
								}
							});		
							
						if(_adminSkins.Contains(skin.Value))
							container.Add(new CuiPanel
							{
								RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "1.5 -7.5", OffsetMax = "7.5 -1.5" },
								Image = { Color = "0.9 0 0 1", Sprite = "assets/icons/circle_closed.png" },
							}, ".Skin");
						else if(_vipSkins.Contains(skin.Value))
							container.Add(new CuiPanel
							{
								RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "1.5 -7.5", OffsetMax = "7.5 -1.5" },
								Image = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/circle_closed.png" },
							}, ".Skin");
						
						xx++;
						
						if(xx == 3)
						{
							xx = 0;
							yy++;
						}
					}
					
					if(kit.Value.Count > 6)
						container.Add(new CuiButton
						{
							RectTransform = { AnchorMin = "1 0.5", AnchorMax = "1 0.5", OffsetMin = "-51.75 -45.5", OffsetMax = "-5 2" },
							Button = { Color = config.GUI.BlockKitColor, Command = $"skin_c kitinfo custom '{kit.Key}'" },
							Text = { Text = $"+{kit.Value.Count - 5}", Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
						}, ".CKit");
						
					container.Add(new CuiButton
					{
						RectTransform = { AnchorMin = "0 0", AnchorMax = "0.5 0", OffsetMin = "5 5", OffsetMax = "-26.75 24.25" },
						Button = { Color = config.GUI.BlockKitColor, Command = $"skin_c setkit custom true false '{kit.Key}'" },
						Text = { Text = lang.GetMessage("INV", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 9, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
					}, ".CKit");
					
					container.Add(new CuiButton
					{
						RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = "-24.583 5", OffsetMax = "24.583 24.25" },
						Button = { Color = config.GUI.BlockKitColor, Command = $"skin_c setkit custom false true '{kit.Key}'" },
						Text = { Text = lang.GetMessage("SET", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 9, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
					}, ".CKit");
					
					container.Add(new CuiButton
					{
						RectTransform = { AnchorMin = "0.5 0", AnchorMax = "1 0", OffsetMin = "26.75 5", OffsetMax = "-5 24.25" },
						Button = { Color = config.GUI.BlockKitColor, Command = $"skin_c setkit custom true true '{kit.Key}'" },
						Text = { Text = lang.GetMessage("INV_SET", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 9, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
					}, ".CKit");
					
					x++;
					
					if(x == (edk ? 3 : 6))
					{
						x = 0;
						y++;
						
						if(y == 2)
							break;
					}
				}
				
				bool back = Page != 0;
				bool next = StoredData[player.userID].Kits.Count > (((Page + 1) * count) - 1);
				
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = "-22.5 5", OffsetMax = "-2.5 25" },
					Button = { Color = back ? config.GUI.ActiveBackColor : config.GUI.InactiveBackColor, Command = back ? $"page.xskinmenu customkits 0 {Page - 1}" : "" },
					Text = { Text = "<", Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = back ? config.GUI.ActiveBackColorText : config.GUI.InactiveBackColorText }
				}, ".CustomKitsGUI");
				
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = "2.5 5", OffsetMax = "22.5 25" },
					Button = { Color = next ? config.GUI.ActiveNextReloadColor : config.GUI.InactiveNextReloadColor, Command = next ? $"page.xskinmenu customkits 0 {Page + 1}" : "" },
					Text = { Text = ">", Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = next ? config.GUI.ActiveNextReloadColorText : config.GUI.InactiveNextReloadColorText }
				}, ".CustomKitsGUI");
			}
			else
				container.Add(new CuiLabel
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-220 -57.5", OffsetMax = "220 12.5" },
					Text = { Text = lang.GetMessage("CUSTOM_KITS_NOPERM", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 20, Color = "0.8 0.8 0.8 0.75060739" }
				}, ".CustomKitsGUI");
			
			CuiHelper.AddUi(player, container);
		}
		 
				
				 
	    private void OnServerInitialized()
		{
			PrintWarning("\n-----------------------------\n" +
			"     Author - Monster\n" +
			"     VK - vk.com/idannopol\n" + 
			"     Discord - Monster#4837\n" +
			"-----------------------------"); 
		   		 		  						  	   		   		 		  		 			   					  	 	 
			if(Interface.Oxide.DataFileSystem.ExistsDatafile("XDataSystem/XSkinMenu/Friends"))
                StoredDataFriends = Interface.Oxide.DataFileSystem.ReadObject<Dictionary<ulong, bool>>("XDataSystem/XSkinMenu/Friends");			
			if(Interface.Oxide.DataFileSystem.ExistsDatafile("XDataSystem/XSkinMenu/Skins"))
                StoredDataSkins = Interface.Oxide.DataFileSystem.ReadObject<Dictionary<string, List<ulong>>>("XDataSystem/XSkinMenu/Skins");
			if(Interface.Oxide.DataFileSystem.ExistsDatafile("XDataSystem/XSkinMenu/SkinsName"))
				StoredDataSkinsName = Interface.Oxide.DataFileSystem.ReadObject<Dictionary<ulong, string>>("XDataSystem/XSkinMenu/SkinsName");
			
			foreach(var items in config.Category)
				foreach(var item in items.Value)
				{
					string key = item.Key;
					
					if(!StoredDataSkins.ContainsKey(key))
						StoredDataSkins[key] = new List<ulong>();
					
					_items.Add(key, item.Value);
					_itemsId.Add(key, ItemManager.FindItemDefinition(key).itemid);
				}
			
			foreach(ItemDefinition item in ItemManager.GetItemDefinitions())
			{
				var prefab = item.GetComponent<ItemModDeployable>()?.entityPrefab?.resourcePath;
				if(string.IsNullOrEmpty(prefab)) continue;
				 
				var shortPrefabName = Utility.GetFileNameWithoutExtension(prefab);
				if(!string.IsNullOrEmpty(shortPrefabName) && !_shortnamesEntity.ContainsKey(shortPrefabName))
				    _shortnamesEntity.Add(shortPrefabName, item.shortname);
			}
			
			foreach(ItemDefinition item in ItemManager.GetItemDefinitions())
			{
				if(item.shortname == "snowmobile") continue;
				
				foreach(var skin in ItemSkinDirectory.ForItem(item))
				{
					ItemSkin itemSkin = skin.invItem as ItemSkin;
					
					if(itemSkin != null && itemSkin.Redirect != null)
						_redirectSkins.Add(itemSkin.Redirect.shortname, item.shortname);
				}
			}
			  
			GenerateItems();
				
			BasePlayer.activePlayerList.ToList().ForEach(OnPlayerConnected);
			timer.Every(180, () => BasePlayer.activePlayerList.ToList().ForEach(SaveData));
			timer.Every(200, () =>
			{
				if(StoredDataFriends != null && StoredDataFriends.Count != 0)
					Interface.Oxide.DataFileSystem.WriteObject("XDataSystem/XSkinMenu/Friends", StoredDataFriends);
			});
			
			if(config.Setting.UseImageLibrary && !ImageLibrary)
			{
				timer.Once(2, () =>
				{
					PrintError(LanguageEnglish ? "You don't have the plugin installed - ImageLibrary!" : "У вас не установлен плагин - ImageLibrary!");
					Interface.Oxide.UnloadPlugin(Name);
				});
				
				return;
			}
			
			InitializeLang();
			permission.RegisterPermission(permUse, this);
			permission.RegisterPermission(permSetting, this);
			permission.RegisterPermission(permCraft, this);
			permission.RegisterPermission(permEntity, this);
			permission.RegisterPermission(permSkinI, this);
			permission.RegisterPermission(permInv, this);   
			permission.RegisterPermission(permGive, this);
			permission.RegisterPermission(permPickup, this);
			permission.RegisterPermission(permSkinC, this);
			permission.RegisterPermission(permAdminS, this);
			permission.RegisterPermission(permVipS, this);
			permission.RegisterPermission(permAdmin, this);
			permission.RegisterPermission(permSprayC, this);
			permission.RegisterPermission(permKitsD, this);
			permission.RegisterPermission(permKitsC, this);
			permission.RegisterPermission(permPlayerAdd, this);
			
			foreach(var perm in config.KitsCPSetting)
				permission.RegisterPermission(perm.Key, this);
			
			NextTick(() =>
			{
				BgMainLayer = config.GUI.BColor0 != "0 0 0 0" || config.GUI.CloseUI ? ".BgGUIS" : ".GUIS";
				BgKitsLayer = config.GUI.BColor0 != "0 0 0 0" || config.GUI.CloseUI ? ".BgKitsBGUI" : ".KitsBGUI";
				
				if(!config.Setting.RepairBench)
					Unsubscribe(nameof(OnItemSkinChange));
				
				if(!config.Setting.ReskinRespawn)
					Unsubscribe(nameof(OnPlayerRespawned));
			});
			
			foreach(string command in config.Setting.SkinCommandList)
				cmd.AddChatCommand(command, this, cmdOpenGUI);
			
			foreach(string command in config.Setting.SkinKitCommandList)
				cmd.AddChatCommand(command, this, cmdOpenKitsGUI);
			
			foreach(string command in config.Setting.SkinEntityCommandList)
				cmd.AddChatCommand(command, this, cmdSetSkinEntity);
			
			foreach(string command in config.Setting.SkinItemCommandList)
				cmd.AddChatCommand(command, this, cmdSetSkinItem);
			
			timer.Once(5, () => ManagementSkins());
		}
		
        private Dictionary<string, string> _workshopNameToShortname = new Dictionary<string, string>
        {
			["bandana"] = "mask.bandana",
			["balaclava"] = "mask.balaclava",
			["beeniehat"] = "hat.beenie",
			["burlapshoes"] = "burlap.shoes",
			["burlapshirt"] = "burlap.shirt",
			["burlappants"] = "burlap.trousers",
			["burlapheadwrap"] = "burlap.headwrap",
			["buckethelmet"] = "bucket.helmet",
			["booniehat"] = "hat.boonie",
			["cap"] = "hat.cap",
			["collaredshirt"] = "shirt.collared",
			["coffeecanhelmet"] = "coffeecan.helmet",
			["deerskullmask"] = "deer.skull.mask",
			["hideskirt"] = "attire.hide.skirt",
			["hideshirt"] = "attire.hide.vest",
			["hidepants"] = "attire.hide.pants",
			["hideshoes"] = "attire.hide.boots",
			["hidehalterneck"] = "attire.hide.helterneck",
			["hoodie"] = "hoodie",
			["hideponcho"] = "attire.hide.poncho",
			["leathergloves"] = "burlap.gloves",
			["longtshirt"] = "tshirt.long",
			["metalchestplate"] = "metal.plate.torso",
			["metalfacemask"] = "metal.facemask",
			["minerhat"] = "hat.miner",
			["pants"] = "pants",
			["roadsignvest"] = "roadsign.jacket",
			["roadsignpants"] = "roadsign.kilt",
			["riothelmet"] = "riot.helmet",
			["snowjacket"] = "jacket.snow",
			["shorts"] = "pants.shorts",
			["tanktop"] = "shirt.tanktop",
			["tshirt"] = "tshirt",
			["vagabondjacket"] = "jacket",
			["workboots"] = "shoes.boots",
			["ak47"] = "rifle.ak",
			["boltrifle"] = "rifle.bolt",
			["boneclub"] = "bone.club",
			["boneknife"] = "knife.bone",
			["crossbow"] = "crossbow",
			["doublebarrelshotgun"] = "shotgun.double",
			["eokapistol"] = "pistol.eoka",
			["f1grenade"] = "grenade.f1",
			["longsword"] = "longsword",
			["mp5"] = "smg.mp5",
			["pumpshotgun"] = "shotgun.pump",
			["rock"] = "rock",
			["salvagedhammer"] = "hammer.salvaged",
			["salvagedicepick"] = "icepick.salvaged",
			["satchelcharge"] = "explosive.satchel",
			["semiautomaticpistol"] = "pistol.semiauto",
			["stonehatchet"] = "stonehatchet",
			["stonepickaxe"] = "stone.pickaxe",
			["largewoodbox"] = "box.wooden.large",
			["reactivetarget"] = "target.reactive",
			["sandbagbarricade"] = "barricade.sandbags",
			["sleepingbag"] = "sleepingbag",
			["sheetmetaldoor"] = "door.hinged.metal",
			["waterpurifier"] = "water.purifier",
			["woodstoragebox"] = "box.wooden",
			["woodendoor"] = "door.hinged.wood",
			["acousticguitar"] = "fun.guitar",
			["pickaxe"] = "pickaxe",
			["hatchet"] = "hatchet",
			["revolver"] = "pistol.revolver",
			["rocketlauncher"] = "rocket.launcher",
			["semiautomaticrifle"] = "rifle.semiauto",
			["waterpipeshotgun"] = "shotgun.waterpipe",
			["customsmg"] = "smg.2",
			["python"] = "pistol.python",
			["lr300"] = "rifle.lr300",
			["combatknife"] = "knife.combat",
			["armoreddoor"] = "door.hinged.toptier",
			["concretebarricade"] = "barricade.concrete",
			["thompson"] = "smg.thompson",
			["hammer"] = "hammer",
			["sword"] = "salvaged.sword",
			["huntingbow"] = "bow.hunting",
			["m249"] = "lmg.m249",
			["m39"] = "rifle.m39",
			["l96"] = "rifle.l96",
			["locker"] = "locker",
			["vendingmachine"] = "vending.machine",
			["fridge"] = "fridge",
			["garagedoor"] = "wall.frame.garagedoor",
			["armoreddoubledoor"] = "door.double.hinged.toptier",
			["sheetmetaldoubledoor"] = "door.double.hinged.metal",
			["woodendoubledoor"] = "door.double.hinged.wood",
			["furnace"] = "furnace",
			["jackhammer"] = "jackhammer",
			["table"] = "table",
			["roadsigngloves"] = "roadsign.gloves",
			["bearrug"] = "rug.bear",
			["rug"] = "rug",
			["chair"] = "chair",
			["spinningwheel"] = "spinner.wheel",
			["largebackpack"] = "largebackpack",
			["wallpaperwall"] = "wallpaper.wall",
			["wallpaperflooring"] = "wallpaper.flooring",
			["wallpaperceiling"] = "wallpaper.ceiling",
			["bed"] = "bed",
			["hmlmg"] = "hmlmg",
			["tacticalgloves"] = "tactical.gloves",
			["sks"] = "rifle.sks",
			["barbeque"] = "bbq",
			["salvagedaxe"] = "axe.salvaged",
			["electricfurnace"] = "electric.furnace",
			["autoturret"] = "autoturret",
			["metalshopfront"] = "wall.frame.shopfront.metal",
			["smallbackpack"] = "smallbackpack",
			["woodarmorhelmet"] = "wood.armor.helmet",
			["woodarmorjacket"] = "wood.armor.jacket",
			["woodarmorpants"] = "wood.armor.pants",
			["woodarmorgloves"] = "woodarmor.gloves"
        };
		
		private void cmdOpenKitsGUI(BasePlayer player, string command, string[] args)
		{
			if(!permission.UserHasPermission(player.UserIDString, permUse))
				SendReply(player, lang.GetMessage("NOPERM", this, player.UserIDString));
			else
			{
				if(config.Setting.EnableDefaultKits || config.Setting.EnableCustomKits)
					SkinKitsGUI(player, true);
			}
		}
		
		private void OnUserGroupRemoved(string id, string groupName)
		{
			BasePlayer player = BasePlayer.FindByID(ulong.Parse(id));
			
			if(player != null)
				ResetPlayerSkins(player);
		}
		
		private BaseEntity GetRHitEntity(BasePlayer player)
		{
			RaycastHit rhit;
			
			if(!Physics.Raycast(player.eyes.HeadRay(), out rhit, 3f, LayerMask.GetMask("Deployed", "Construction", "Prevent Building"))) return null;
			
			return rhit.GetEntity();
		}
		
		private void ParseBatchWEBRequestResult(string response, HashSet<ulong> invalidSkins)
		{
			SkinsQueryResponse sQR = JsonConvert.DeserializeObject<SkinsQueryResponse>(response);
			
			if(sQR?.response != null && sQR.response.publishedfiledetails != null && sQR.response.publishedfiledetails.Length != 0)
			{
				foreach(PublishedFileDetails publishedFileDetails in sQR.response.publishedfiledetails)
				{
					ulong skinID = Convert.ToUInt64(publishedFileDetails.publishedfileid);
					
					if(publishedFileDetails.result != 1 || publishedFileDetails.consumer_app_id != 252490)
						invalidSkins.Add(skinID);
					else
					{
						_allCommunitySkinsURL[skinID] = publishedFileDetails.preview_url + _partURL;
						
						if(!StoredDataSkinsName.ContainsKey(skinID))
							StoredDataSkinsName[skinID] = GetCleanSkinName(publishedFileDetails.title);
					}
				}
			}
		}
		
				
		 
        private void InitializeLang()
        {
            lang.RegisterMessages(new Dictionary<string, string>
            {
                ["TITLE"] = "COOL SERVER SKINS MENU",
				["SETINFO"] = "YOU CAN CUSTOMIZE THE MENU DEPENDING ON THE SITUATION!",
				["ERRORSKIN"] = "THE SKIN YOU CHOSE CAN BE CHANGED ONLY IN THE INVENTORY OR WHEN CRAFTING!",
				["CLEARALL"] = "RESET ALL SELECTED SKINS",
				["NOPERM"] = "No permissions!",
				["NEXT"] = "NEXT",
				["BACK"] = "BACK",
				["DEFAULT_MENU"] = "DEFAULT MENU",
				["COMFORT_MENU"] = "COMFORT MENU",
                ["weapon"] = "WEAPON",
                ["construction"] = "CONSTRUCTION",
                ["item"] = "ITEM",
                ["attire"] = "ATTIRE",
                ["tool"] = "TOOL",
                ["transport"] = "TRANSPORT",
                ["inventory"] = "CHANGE ITEM SKIN IN INVENTORY AFTER SELECTING SKIN IN MENU",
				["clear"] = "CHANGE ITEM SKIN IN INVENTORY AFTER REMOVING SKIN IN MENU",
                ["entity"] = "CHANGE SKIN ON INSTALLED ITEMS/CONSTRUCTIONS   [ /SKINENTITY ]",
                ["craft"] = "CHANGE ITEM SKIN WHEN CRAFTING",
                ["give"] = "CHANGE ITEM SKIN WHEN IT IS PLACED IN THE INVENTORY BY ANY MEANS",
				["friends"] = "ALLOW FRIENDS TO CHANGE THE SKIN ON ITEMS/CONSTRUCTIONS YOU INSTALLED   [ /SKINENTITY ]",
				["spraycan"] = "USE SKINS WITH A SPRAY CAN",
				["sound"] = "ENABLE SOUND EFFECTS IN THE MENU   [ CLICKS ]",
				["pickup"] = "CHANGE ITEM SKIN ONLY WHEN PICKUP",
				["giveno"] = "DO NOT RESET ITEM SKIN FOR WHICH NO SKIN IS SELECTED WHEN IT ENTERS THE INVENTORY",
				["MAX_KITS"] = "YOU CANNOT CREATE A NEW KIT OF SKINS! LIMIT REACHED.",
				["KIT_NAME_A_E"] = "A KIT OF SKINS WITH THE NAME <color=orange>{0}</color> ALREADY EXISTS!",
				["KIT_EMPTY"] = "YOU CAN'T CREATE AN EMPTY KIT OF SKINS!",
				["DESC_DEFAULT_KITS"] = "DEFAULT SKIN KITS",
				["DEFAULT_KITS_NOPERM"] = "YOU HAVE NO PERMISSION TO USE THE DEFAULT SKIN KITS!",
				["DESC_CUSTOM_KITS"] = "PERSONAL SKIN KITS",
				["CUSTOM_KITS_AMOUNT"] = "CREATED {0}/{1} SKIN KITS",
				["CUSTOM_KITS_NOPERM"] = "YOU DON'T HAVE PERMISSION TO USE PERSONAL SKIN KITS!",
				["CREATE_KIT_INFO"] = "THE KIT IS CREATED FROM THE SELECTED SKINS IN THE MENU\nENTER THE NAME OF THE SKIN KIT AND PRESS <b>ENTER</b> TO CREATE A PERSONAL SKIN KIT",
				["SKIN_SEARCH"] = "SKIN SEARCH   [ NAME OR ID ]",
				["INV"] = "INVENTORY",
				["SET"] = "SET",
				["INV_SET"] = "INV + SET",
				["KIT_NAME_CREATE"] = "Kit name 001",
				["WAIT_ADD"] = "Wait!",
				["SUCCESSFUL_ADD"] = "Your request has been sent, if it is correct - we will add skins that are not yet in the list!",
				["PLAYER_ADD_SKIN"] = "ADD SKIN - ID",
				["PLAYER_ADD_COLLECTION"] = "ADD COLLECTION - ID"
            }, this);
		   		 		  						  	   		   		 		  		 			   					  	 	 
            lang.RegisterMessages(new Dictionary<string, string>
            {
                ["TITLE"] = "МЕНЮ СКИНОВ КРУТОГО СЕРВЕРА",
                ["SETINFO"] = "ВЫ МОЖЕТЕ КАСТОМНО НАСТРАИВАТЬ МЕНЮ В ЗАВИСИМОСТИ ОТ СИТУАЦИИ!",
                ["ERRORSKIN"] = "ВЫБРАННЫЙ ВАМИ СКИН МОЖНО ИЗМЕНИТЬ ТОЛЬКО В ИНВЕНТАРЕ ИЛИ ПРИ КРАФТЕ!",
                ["CLEARALL"] = "СБРОСИТЬ ВСЕ ВЫБРАННЫЕ СКИНЫ",
				["NOPERM"] = "Недостаточно прав!",
				["NEXT"] = "ДАЛЕЕ",
				["BACK"] = "НАЗАД",
				["DEFAULT_MENU"] = "СТАНДАРТНОЕ МЕНЮ",
				["COMFORT_MENU"] = "КОМФОРТНОЕ МЕНЮ",
                ["weapon"] = "ОРУЖИЕ",
                ["construction"] = "СТРОИТЕЛЬСТВО",
                ["item"] = "ПРЕДМЕТЫ",
                ["attire"] = "ОДЕЖДА",
                ["tool"] = "ИНСТРУМЕНТЫ",
				["transport"] = "ТРАНСПОРТ",
                ["inventory"] = "ИЗМЕНИТЬ СКИН ПРЕДМЕТА В ИНВЕНТАРЕ ПОСЛЕ ВЫБОРА СКИНА В МЕНЮ",
				["clear"] = "ИЗМЕНИТЬ СКИН ПРЕДМЕТА В ИНВЕНТАРЕ ПОСЛЕ УДАЛЕНИЯ СКИНА В МЕНЮ",
                ["entity"] = "ИЗМЕНИТЬ СКИН НА УСТАНОВЛЕННЫХ ПРЕДМЕТАХ/КОНСТРУКЦИЯХ   [ /SKINENTITY ]",
                ["craft"] = "ИЗМЕНИТЬ СКИН ПРЕДМЕТА ПРИ КРАФТЕ",
                ["give"] = "ИЗМЕНИТЬ СКИН ПРЕДМЕТА КОГДА ОН ЛЮБЫМ СПОСОБОМ ПОПАЛ В ИНВЕНТАРЬ",
                ["friends"] = "РАЗРЕШИТЬ ДРУЗЬЯМ ИЗМЕНИТЬ СКИН НА УСТАНОВЛЕННЫХ ВАМИ ПРЕДМЕТАХ/КОНСТРУКЦИЯХ   [ /SKINENTITY ]",
				["spraycan"] = "ИСПОЛЬЗОВАТЬ СКИНЫ С ПОМОЩЬЮ БАЛЛОНЧИКА",
				["sound"] = "ВКЛЮЧИТЬ ЗВУКОВЫЕ ЭФФЕКТЫ В МЕНЮ   [ ЩЕЛЧКИ ]",
				["pickup"] = "ИЗМЕНИТЬ СКИН ПРЕДМЕТА ТОЛЬКО ПРИ ЕГО ПОДБОРЕ",
				["giveno"] = "НЕ СБРАСЫВАТЬ СКИН ПРЕДМЕТА, ДЛЯ КОТОРОГО НЕ ВЫБРАН СКИН, КОГДА ОН ПОПАДАЕТ В ИНВЕНТАРЬ",
				["MAX_KITS"] = "ВЫ НЕ МОЖЕТЕ СОЗДАТЬ НОВЫЙ НАБОР СКИНОВ! ДОСТИГНУТ ЛИМИТ.",
				["KIT_NAME_A_E"] = "НАБОР СКИНОВ С ИМЕНЕМ <color=orange>{0}</color> УЖЕ СУЩЕСТВУЕТ!",
				["KIT_EMPTY"] = "ВЫ НЕ МОЖЕТЕ СОЗДАТЬ ПУСТОЙ НАБОР СКИНОВ!",
				["DESC_DEFAULT_KITS"] = "СТАНДАРТНЫЕ НАБОРЫ СКИНОВ",
				["DEFAULT_KITS_NOPERM"] = "У ВАС НЕТ ПРАВ ЧТОБЫ ИСПОЛЬЗОВАТЬ СТАНДАРТНЫЕ НАБОРЫ СКИНОВ!",
				["DESC_CUSTOM_KITS"] = "ЛИЧНЫЕ НАБОРЫ СКИНОВ",
				["CUSTOM_KITS_AMOUNT"] = "СОЗДАНО {0}/{1} НАБОРОВ СКИНОВ",
				["CUSTOM_KITS_NOPERM"] = "У ВАС НЕТ ПРАВ ЧТОБЫ ИСПОЛЬЗОВАТЬ ЛИЧНЫЕ НАБОРЫ СКИНОВ!",
				["CREATE_KIT_INFO"] = "НАБОР СОЗДАЕТСЯ ИЗ ВЫБРАННЫХ СКИНОВ В МЕНЮ\nВВЕДИТЕ ИМЯ НАБОРА И НАЖМИТЕ <b>ENTER</b>, ЧТОБЫ СОЗДАТЬ ЛИЧНЫЙ НАБОР СКИНОВ",
				["SKIN_SEARCH"] = "ПОИСК СКИНОВ   [ ИМЯ ИЛИ ID ]",
				["INV"] = "INVENTORY",
				["SET"] = "SET",
				["INV_SET"] = "INV + SET",
				["KIT_NAME_CREATE"] = "Kit name 001",
				["WAIT_ADD"] = "Подождите!",
				["SUCCESSFUL_ADD"] = "Ваш запрос отправлен, если он корректный - мы добавим скины, которых ещё нет в списке!",
				["PLAYER_ADD_SKIN"] = "ДОБАВИТЬ СКИН - ID",
				["PLAYER_ADD_COLLECTION"] = "ДОБАВИТЬ КОЛЛЕКЦИЮ - ID"
            }, this, "ru");
			
			lang.RegisterMessages(new Dictionary<string, string>
            {
                ["TITLE"] = "МЕНЮ СКІНІВ КРУТОГО СЕРВЕРУ",
                ["SETINFO"] = "ВИ МОЖЕТЕ КАСТОМНО НАЛАШТУВАТИ МЕНЮ В ЗАЛЕЖНОСТІ ВІД СИТУАЦІЇ!",
                ["ERRORSKIN"] = "ВИБРАНИЙ ВАМИ СКІН МОЖНА ЗМІНИТИ ТІЛЬКИ В ІНВЕНТАРІ АБО ПРИ КРАФТІ!",
                ["CLEARALL"] = "СКИНУТИ ВСІ ОБРАНІ СКІНИ",
				["NOPERM"] = "Недостатньо прав!",
				["NEXT"] = "ДАЛІ",
				["BACK"] = "НАЗАД",
				["DEFAULT_MENU"] = "СТАНДАРТНЕ МЕНЮ",
				["COMFORT_MENU"] = "КОМФОРТНЕ МЕНЮ",
                ["weapon"] = "ЗБРОЯ",
                ["construction"] = "БУДІВНИЦТВО",
                ["item"] = "ПРЕДМЕТИ",
                ["attire"] = "ОДЯГ",
                ["tool"] = "ІНСТРУМЕНТИ",
				["transport"] = "ТРАНСПОРТ",
                ["inventory"] = "ЗМІНИТИ СКІН ПРЕДМЕТА В ІНВЕНТАРІ ПІСЛЯ ВИБОРУ СКІНА У МЕНЮ",
				["clear"] = "ЗМІНИТИ СКІН ПРЕДМЕТА В ІНВЕНТАРІ ПІСЛЯ ВИДАЛЕННЯ СКІНА У МЕНЮ",
                ["entity"] = "ЗМІНИТИ СКІН НА ВСТАНОВЛЕНИХ ПРЕДМЕТАХ/КОНСТРУКЦІЯХ   [ /SKINENTITY ]",
                ["craft"] = "ЗМІНИТИ СКІН ПРЕДМЕТА ПРИ КРАФТІ",
                ["give"] = "ЗМІНИТИ СКІН ПРЕДМЕТА КОЛИ ВІН БУДЬ-ЯКИМ СПОСОБОМ ПОТРАПИВ В ІНВЕНТАР",
                ["friends"] = "ДОЗВОЛИТИ ДРУЗЯМ ЗМІНИТИ СКІН НА ВСТАНОВЛЕНИХ ВАМИ ПРЕДМЕТАХ/КОНСТРУКЦІЯХ   [ /SKINENTITY ]",
				["spraycan"] = "ВИКОРИСТОВУВАТИ СКІНИ ЗА ДОПОМОГОЮ БАЛОНЧИКА",
				["sound"] = "ВКЛЮЧИТИ ЗВУКОВІ ЕФЕКТИ У МЕНЮ   [ КЛІКИ ]",
				["pickup"] = "ЗМІНИТИ СКІН ПРЕДМЕТА ТІЛЬКИ ПІД ЧАС ЙОГО ПІДБОРУ",
				["giveno"] = "НЕ СКИДАТИ СКІН ПРЕДМЕТА, ДЛЯ ЯКОГО НЕ ВИБРАНО СКІН, КОЛИ ВІН ПОТРАПЛЯЄ В ІНВЕНТАР",
				["MAX_KITS"] = "ВИ НЕ МОЖЕТЕ СТВОРИТИ НОВИЙ НАБІР СКІНІВ! ДОСЯГНУТО ЛІМІТУ.",
				["KIT_NAME_A_E"] = "НАБІР СКІНІВ З ІМ'ЯМ <color=orange>{0}</color> ВЖЕ ІСНУЄ!",
				["KIT_EMPTY"] = "ВИ НЕ МОЖЕТЕ СТВОРИТИ ПОРОЖНІЙ НАБІР СКІНІВ!",
				["DESC_DEFAULT_KITS"] = "СТАНДАРТНІ НАБОРИ СКІНІВ",
				["DEFAULT_KITS_NOPERM"] = "У ВАС НЕМАЄ ПРАВ ЩОБ ВИКОРИСТОВУВАТИ СТАНДАРТНІ НАБОРИ СКІНІВ!",
				["DESC_CUSTOM_KITS"] = "ОСОБИСТІ НАБОРИ СКІНІВ",
				["CUSTOM_KITS_AMOUNT"] = "СТВОРЕНО {0}/{1} НАБОРІВ СКІНІВ",
				["CUSTOM_KITS_NOPERM"] = "У ВАС НЕМАЄ ПРАВ ЩОБ ВИКОРИСТОВУВАТИ ОСОБИСТІ НАБОРИ СКІНІВ!",
				["CREATE_KIT_INFO"] = "НАБІР СТВОРЮЄТЬСЯ З ОБРАНИХ СКІНІВ У МЕНЮ\nВВЕДІТЬ ІМ'Я НАБОРУ І НАТИСНІТЬ <b>ENTER</b>, ЩОБ СТВОРИТИ ОСОБИСТИЙ НАБІР СКІНІВ",
				["SKIN_SEARCH"] = "ПОШУК СКІНІВ   [ ІМ'Я АБО ID ]",
				["INV"] = "INVENTORY",
				["SET"] = "SET",
				["INV_SET"] = "INV + SET",
				["KIT_NAME_CREATE"] = "Kit name 001",
				["WAIT_ADD"] = "Зачекайте!",
				["SUCCESSFUL_ADD"] = "Ваш запит надіслано, якщо він коректний - ми додамо скіни, яких ще немає в списку!",
				["PLAYER_ADD_SKIN"] = "ДОДАТИ СКІН - ID",
				["PLAYER_ADD_COLLECTION"] = "ДОДАТИ КОЛЕКЦІЮ - ID"
            }, this, "uk");
			
            lang.RegisterMessages(new Dictionary<string, string>
            {
                ["TITLE"] = "NOMBRE DEL SERVIDOR",
				["SETINFO"] = "¡PUEDES PERSONALIZAR EL MENÚ DEPENDIENDO DE LA SITUACIÓN!",
				["ERRORSKIN"] = "¡LA SKIN QUE ELIGIÓ SE PUEDE CAMBIAR SOLO EN EL INVENTARIO O AL CREAR!",
				["CLEARALL"] = "RESTABLECER TODAS LAS SKINS SELECCIONADAS",
				["NOPERM"] = "¡No tienes permisos!",
				["NEXT"] = "SIGUIENTE",
				["BACK"] = "ATRAS",
				["DEFAULT_MENU"] = "MENÚ ESTÁNDAR",
				["COMFORT_MENU"] = "MENÚ CÓMODO",
                ["weapon"] = "ARMAS",
                ["construction"] = "CONSTRUCCIÓN",
                ["item"] = "ITEMS",
                ["attire"] = "ATUENDOS",
                ["tool"] = "HERRAMIENTAS",
                ["transport"] = "TRANSPORTES",
                ["inventory"] = "CAMBIAR LA PIEL DEL ARTÍCULO EN EL INVENTARIO DESPUÉS DE SELECCIONAR LA PIEL EN EL MENÚ",
				["clear"] = "CAMBIAR LA PIEL DEL ARTÍCULO EN EL INVENTARIO DESPUÉS DE QUITAR LA PIEL EN EL MENÚ",
                ["entity"] = "CAMBIO DE PIEL EN ARTÍCULOS/CONSTRUCCIONES INSTALADOS   [ /SKINENTITY ]", 
                ["craft"] = "CAMBIAR LA PIEL DEL OBJETO AL CREAR",
                ["give"] = "CAMBIAR LA PIEL DE UN ARTÍCULO CUANDO SE COLOCA EN EL INVENTARIO POR CUALQUIER MEDIO",
				["friends"] = "PERMITA QUE SUS AMIGOS CAMBIEN EL ASPECTO DE LOS ARTÍCULOS/CONSTRUCCIONES QUE USTED INSTALÓ    [ /SKINENTITY ]",
				["spraycan"] = "UTILIZAR PIELES CON LATA DE AEROSOL",
				["sound"] = "HABILITAR EFECTOS DE SONIDO EN EL MENÚ   [ CLICS ]",
				["pickup"] = "CAMBIAR LA PIEL DE UN OBJETO SÓLO AL COGERLO",
				["giveno"] = "NO RESTABLECER LA APARIENCIA DE UN ARTÍCULO PARA EL QUE NO SE HA SELECCIONADO UNA APARIENCIA CUANDO ENTRA EN EL INVENTARIO",
				["MAX_KITS"] = "¡NO PUEDES CREAR UN NUEVO JUEGO DE SKINS! LÍMITE ALCANZADO.",
				["KIT_NAME_A_E"] = "¡YA EXISTE UN CONJUNTO DE SKINS CON EL NOMBRE <color=orange>{0}</color>!",
				["KIT_EMPTY"] = "¡NO SE PUEDE CREAR UN CONJUNTO VACÍO DE SKINS!",
				["DESC_DEFAULT_KITS"] = "CONJUNTOS DE PIELES POR DEFECTO",
				["DEFAULT_KITS_NOPERM"] = "NO TIENE PERMISO PARA UTILIZAR LOS CONJUNTOS DE PIELES PREDETERMINADOS.",
				["DESC_CUSTOM_KITS"] = "KITS PERSONALES PARA LA PIEL",
				["CUSTOM_KITS_AMOUNT"] = "CREADOS CONJUNTOS DE PIELES {0}/{1}",
				["CUSTOM_KITS_NOPERM"] = "¡NO TIENES PERMISO PARA USAR JUEGOS DE PIEL PERSONALES!",
				["CREATE_KIT_INFO"] = "EL CONJUNTO SE CREA A PARTIR DE LAS PIELES SELECCIONADAS EN EL MENÚ\nENTER THE NAME OF THE SKIN SET AND PRESS <b>ENTER</b> TO CREATE A PERSONAL SKIN SET",
				["SKIN_SEARCH"] = "SKIN SEARCH   [ NAME OR ID ]",
				["INV"] = "INVENTORY",
				["SET"] = "SET",
				["INV_SET"] = "INV + SET",
				["KIT_NAME_CREATE"] = "Kit name 001",
				["WAIT_ADD"] = "¡Espera!",
				["SUCCESSFUL_ADD"] = "Tu solicitud ha sido enviada, si es correcta - ¡añadiremos skins que aún no están en la lista!",
				["PLAYER_ADD_SKIN"] = "AÑADIR SKIN - ID",
				["PLAYER_ADD_COLLECTION"] = "AÑADIR COLECCIÓN - ID"
            }, this, "es-ES");
        }
		
				
				
        public class SkinsQueryResponse
        {
            public SkinsResponse response;
        }
		
		private void ZoomGUI(BasePlayer player, int itemid, ulong skin, bool issetitem)
		{
			CuiElementContainer container = new CuiElementContainer();
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
                Image = { Color = config.GUI.BColor3 }
            }, issetitem ? ".SetItemB" : ".SkinGUI", ".ZoomGUI", ".ZoomGUI");
			
			container.Add(new CuiButton
            {    
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
                Button = { Color = "0 0 0 0", Close = ".ZoomGUI" },
                Text = { Text = "" }
            }, ".ZoomGUI");
			
			if(issetitem)
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-90 -99", OffsetMax = "90 81" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, ".ZoomGUI", ".ZoomIMG");	
			else
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-110 -110", OffsetMax = "110 110" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, ".ZoomGUI", ".ZoomIMG");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "5 5", OffsetMax = "-5 -5" },
                Image = { Color = config.GUI.BColor2 }
            }, ".ZoomIMG", ".IMG");
			
				container.Add(new CuiElement
				{
					Parent = ".IMG",
					Components =
					{
						GetImageComponent(itemid, skin),
						new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "5 5", OffsetMax = "-5 -5" }
					}
				});		
			
			if(StoredDataSkinsName.ContainsKey(skin))
				container.Add(new CuiLabel
				{
					RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "-100 5", OffsetMax = "100 22.5" },
					Text = { Text = StoredDataSkinsName[skin] + $" - [ ID: {skin} ]", Align = TextAnchor.MiddleCenter, FontSize = 14, Font = "robotocondensed-regular.ttf", Color = "0.8 0.8 0.8 1" }
				}, ".ZoomIMG");
			else
				container.Add(new CuiLabel
				{
					RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "0 5", OffsetMax = "0 22.5" },
					Text = { Text = $"[ ID: {skin} ]", Align = TextAnchor.MiddleCenter, FontSize = 14, Font = "robotocondensed-regular.ttf", Color = "0.8 0.8 0.8 1" }
				}, ".ZoomIMG");
			
			CuiHelper.AddUi(player, container);
		}
		
		private void SaveDataSkins()
		{
			if(StoredDataSkins != null && StoredDataSkins.Count != 0)
				Interface.Oxide.DataFileSystem.WriteObject("XDataSystem/XSkinMenu/Skins", StoredDataSkins);
		}
		private const string permPickup = "xskinmenu.pickup";
		
		public readonly Dictionary<string, string> errorshortnames = new Dictionary<string, string> { ["snowmobiletomaha"] = "tomahasnowmobile" };
		private Dictionary<string, string> _redirectSkins = new Dictionary<string, string>();
		
		private Dictionary<ulong, Data> StoredData = new Dictionary<ulong, Data>();
		
		private void AddRemoveSkinsRequest(int code, string response, bool addOrRemoveSkin, int countAll)
		{
			if(code == 200 && response != null)
			{
				SkinsQueryResponse sQR = JsonConvert.DeserializeObject<SkinsQueryResponse>(response);
				
				if(sQR?.response == null || sQR.response.publishedfiledetails == null || sQR.response.publishedfiledetails.Length == 0)
					PrintError(LanguageEnglish ? ">>> ERROR ADDING/REMOVING SKINS <<<" : ">>> ОШИБКА ПРИ ДОБАВЛЕНИИ/УДАЛЕНИИ СКИНОВ <<<");
				else
				{
					int count = SkinParse(sQR, addOrRemoveSkin);
					
					if(addOrRemoveSkin)
						PrintWarning(LanguageEnglish ? $"Successfully added {count}/{countAll} skins." : $"Успешно добавлено {count}/{countAll} скинов.");
					else
						PrintWarning(LanguageEnglish ? $"Successfully removed {count}/{countAll} skins." : $"Успешно удалено {count}/{countAll} скинов.");
					
					SaveDataSkins();
					SaveDataSkinsName();
				}
			}
		}
		
		private void SettingGUI(BasePlayer player)
		{
			CuiHelper.DestroyUi(player, ".SkinGUI");
			CuiHelper.DestroyUi(player, ".ItemGUI");
            CuiElementContainer container = new CuiElementContainer();
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-502.5 -228.25", OffsetMax = "502.5 177.5" },
                Image = { Color = "0 0 0 0" }
            }, ".SGUI", ".SettingGUI", ".SettingGUI");
			
			container.Add(new CuiLabel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-300 -105.25", OffsetMax = "300 -80.25" },
                Text = { Text = lang.GetMessage("SETINFO", this, player.UserIDString), Align = TextAnchor.LowerCenter, Font = "robotocondensed-regular.ttf", FontSize = 12, Color = "0.75060739 0.75060739 0.75060739 0.4" }
            }, ".SettingGUI");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-400 -85.25", OffsetMax = "400 154.75" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".SettingGUI", ".SGUIM");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "5 5", OffsetMax = "-5 -5" },
                Image = { Color = config.GUI.BColor2 }
            }, ".SGUIM", ".SGUIMM");
			
			var player_data = StoredData[player.userID];
			
			Dictionary<string, bool> setting = new Dictionary<string, bool>
			{
				["inventory"] = player_data.ChangeSI,
				["clear"] = player_data.ChangeSCL,
				["entity"] = player_data.ChangeSE,
				["friends"] = StoredDataFriends[player.userID],
				["give"] = player_data.ChangeSG,
				["pickup"] = player_data.ChangeSP,
				["giveno"] = player_data.ChangeSGN,
				["craft"] = player_data.ChangeSC,
				["spraycan"] = player_data.UseSprayC,
				["sound"] = player_data.UseSoundE
			};
			
			int x = 0, y = 0;
			
			foreach(var s in setting) 
			{		
			    container.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = $"{-390 + (x * 392.5)} {70 - (y * 45)}", OffsetMax = $"{-2.5 + (x * 392.5)} {110 - (y * 45)}" },
                    Image = { Color = config.GUI.SettingColor, Material = "assets/icons/greyout.mat" }
                }, ".SGUIMM", ".SM");
				
				container.Add(new CuiButton
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "0 1", OffsetMin = "5 5", OffsetMax = "35 -5" },
                    Button = { Color = s.Value ? config.MenuS.CTButton : config.MenuS.CFButton, Sprite = s.Value ? config.MenuS.TButtonIcon : config.MenuS.FButtonIcon, Command = $"skin_s {s.Key}" },
                    Text = { Text = "" }
                }, ".SM");				
				
			    container.Add(new CuiLabel
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "42.5 2.5", OffsetMax = "-2.5 -2.5" },
                    Text = { Text = lang.GetMessage(s.Key, this, player.UserIDString), Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 11, Color = "0.75060739 0.75060739 0.75060739 1" }
                }, ".SM");
				
				x++;
				
				if(x == 2)
				{
					x = 0;
					y++;
				}
			}
			
						
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = "-230 5", OffsetMax = "230 51.75" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".SettingGUI", ".ComfortMenu");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "5 5", OffsetMax = "-5 -5" },
                Image = { Color = config.GUI.BColor2 }
            }, ".ComfortMenu", ".Comfort");
			
			container.Add(new CuiButton
            {    
                RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 1", OffsetMin = "-220 5", OffsetMax = "-7.5 -5" },
                Button = { Color = !player_data.Comfort ? config.GUI.ActiveNextReloadColor : config.GUI.ActiveBackColor, Command = "skin_s defaultmenu" },
                Text = { Text = lang.GetMessage("DEFAULT_MENU", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = !player_data.Comfort ? config.GUI.ActiveNextReloadColorText : config.GUI.ActiveBackColorText }
            }, ".Comfort");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 1", OffsetMin = "-2.5 0", OffsetMax = "2.5 0" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".Comfort");
			
			container.Add(new CuiButton
            {    
                RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 1", OffsetMin = "7.5 5", OffsetMax = config.GUI.ButtonComfortPlus ? "178.25 -5" : "220 -5" },
                Button = { Color = player_data.Comfort ? config.GUI.ActiveNextReloadColor : config.GUI.ActiveBackColor, Command = "skin_s comfortmenu" },
                Text = { Text = lang.GetMessage("COMFORT_MENU", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = player_data.Comfort ? config.GUI.ActiveNextReloadColorText : config.GUI.ActiveBackColorText }
            }, ".Comfort");
			
			if(config.GUI.ButtonComfortPlus)
			{
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 1", OffsetMin = "183.25 0", OffsetMax = "188.25 0" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, ".Comfort");
				
				container.Add(new CuiButton
				{    
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 1", OffsetMin = "193.25 5", OffsetMax = "220 -5" },
					Button = { Color = player_data.ComfortP ? config.MenuS.CTButton : config.MenuS.CFButton, Sprite = "assets/icons/add.png", Command = "skin_s comfortplusmenu" },
					Text = { Text = "" }
				}, ".Comfort");
			}
			
						
			CuiHelper.AddUi(player, container);
		}
		
				
				
		private void GUI(BasePlayer player)
		{
			CuiHelper.DestroyUi(player, BgKitsLayer);
			
            CuiElementContainer container = new CuiElementContainer(); 
			
			bool bcOn = config.GUI.ButtonClear, xbsmLoad = XBuildingSkinMenu;
			
			if(config.GUI.BColor0 != "0 0 0 0" || config.GUI.CloseUI)
			{
				container.Add(new CuiPanel
				{
					CursorEnabled = true,
					RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
					Image = { Color = config.GUI.BColor0, Material = config.GUI.BMaterial0 }
				}, config.GUI.LayerUI, ".BgGUIS", ".BgGUIS");
				
				if(config.GUI.CloseUI)
					container.Add(new CuiButton
					{
						RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
						Button = { Color = "0 0 0 0", Close = ".BgGUIS" },
						Text = { Text = "" }
					}, ".BgGUIS");
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-507.5 -260", OffsetMax = "507.5 290" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, ".BgGUIS", ".GUIS");
			}
			else
				container.Add(new CuiPanel
				{
					CursorEnabled = true,
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-507.5 -260", OffsetMax = "507.5 290" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, config.GUI.LayerUI, ".GUIS", ".GUIS");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "5 5", OffsetMax = "-5 -5" },
                Image = { Color = config.GUI.BColor2 }
            }, ".GUIS", ".SGUI");
			
			container.Add(new CuiButton
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "470 237.5", OffsetMax = "497.5 265" },
                Button = { Color = config.GUI.IconColor, Sprite = "assets/icons/close.png", Close = BgMainLayer },
                Text = { Text = "" }
            }, ".SGUI");
			
			container.Add(new CuiButton
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-497.5 237.5", OffsetMax = "-470 265" },
                Button = { Color = config.GUI.IconColor, Sprite = "assets/icons/gear.png", Command = "skin_s open" },
                Text = { Text = "" }
            }, ".SGUI");
			
			bool enablekits = config.Setting.EnableDefaultKits || config.Setting.EnableCustomKits;
			
			if(enablekits)
			{
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-455 237.5", OffsetMax = "-427.5 265" },
					Button = { Color = config.GUI.IconColor, Sprite = config.GUI.IconKits, Command = "skin_c openkit" },
					Text = { Text = "" }
				}, ".SGUI");
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-422.5 227.5", OffsetMax = "-417.5 275" },
					Image = { Color = config.GUI.BColor1, Material = "assets/content/ui/uibackgroundblur.mat" }
				}, ".SGUI");
			}
			
			container.Add(new CuiLabel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = enablekits ? "-417.5 232.5" : "-460 232.5", OffsetMax = xbsmLoad ? "417.5 270" : "460 270" },
                Text = { Text = lang.GetMessage("TITLE", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 18, Color = "1 1 1 0.75060739" }
            }, ".SGUI");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-507.5 227.5", OffsetMax = "507.5 232.5" },
                Image = { Color = config.GUI.BColor1, Material = "assets/content/ui/uibackgroundblur.mat" }
            }, ".SGUI");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-507.5 177.5", OffsetMax = "507.5 182.5" },
                Image = { Color = config.GUI.BColor1, Material = "assets/content/ui/uibackgroundblur.mat" }
            }, ".SGUI");
		   		 		  						  	   		   		 		  		 			   					  	 	 
			if(xbsmLoad)
			{
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "427.5 237.5", OffsetMax = "455 265" },
					Button = { Color = config.GUI.IconColor, Sprite = config.GUI.IconBSkin, Command = "xbuildingskinmenu_open", Close = BgMainLayer },
					Text = { Text = "" }
				}, ".SGUI");
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "417.5 227.5", OffsetMax = "422.5 275" },
					Image = { Color = config.GUI.BColor1, Material = "assets/content/ui/uibackgroundblur.mat" }
				}, ".SGUI");
			}
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "460 227.5", OffsetMax = "465 275" },
                Image = { Color = config.GUI.BColor1, Material = "assets/content/ui/uibackgroundblur.mat" }
            }, ".SGUI");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-465 227.5", OffsetMax = "-460 275" },
                Image = { Color = config.GUI.BColor1, Material = "assets/content/ui/uibackgroundblur.mat" }
            }, ".SGUI");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMin = "0 36.75", OffsetMax = "0 41.75" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".SGUI");
			
			if(bcOn)
			{
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "0 0", OffsetMin = "5 5", OffsetMax = "190 31.75" },
					Button = { Color = config.GUI.ActiveNextReloadColor, Command = "skin_c clearall" },
					Text = { Text = lang.GetMessage("CLEARALL", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 11, Font = "robotocondensed-regular.ttf", Color = config.GUI.ActiveNextReloadColorText }
				}, ".SGUI");
			
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "0 0", OffsetMin = "195 0", OffsetMax = "200 36.75" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, ".SGUI");
			}
			
			if(permission.UserHasPermission(player.UserIDString, permAdmin))
			{
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "0 0", OffsetMin = bcOn ? "205 5" : "5 5", OffsetMax = bcOn ? "231.75 31.75" : "31.75 31.75" },
					Button = { Color = _adminUiFD.Contains(player.userID) ? "0.5 0.5 0.5 0.6" : "0.75 0.95 0.41 0.8", Sprite = "assets/icons/power.png", Command = "skin_c adminf" },
					Text = { Text = "" }
				}, ".SGUI");
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "0 0", OffsetMin = bcOn ? "236.75 0" : "36.75 0", OffsetMax = bcOn ? "241.75 36.75" : "41.75 36.75" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, ".SGUI");
			}
			
			CuiHelper.AddUi(player, container);
			
			CategoryGUI(player);
			if(config.Category.Count != 0) ItemGUI(player, config.Category.ElementAt(0).Key);
		}
		
		//FP_TOS
		private List<ulong> _freeSkins = new List<ulong> { 10246, 10243, 10242, 10386, 10372, 10384, 10401, 10406, 10388 };
		
				
				
				
		private int SkinParse(SkinsQueryResponse sQR, bool addOrRemove)
		{
			int count = 0;
			
			foreach(PublishedFileDetails publishedFileDetails in sQR.response.publishedfiledetails)
				if(publishedFileDetails.tags != null)
					foreach(PublishedFileDetails.Tag tag in publishedFileDetails.tags)
					{
						string Tag = tag.tag.ToLower().Replace("skin", "").Replace(" ", "").Replace("-", "").Replace(".item", "");
						
						if(!string.IsNullOrEmpty(Tag) && _workshopNameToShortname.ContainsKey(Tag))
						{
							string shortname = _workshopNameToShortname[Tag];
							ulong skinID = Convert.ToUInt64(publishedFileDetails.publishedfileid);
							
							//FP_TOS
							if(config.Setting.FacepunchTOS && _allFPSkins.Contains(skinID)) continue;
							
							if(!StoredDataSkins.ContainsKey(shortname))
								StoredDataSkins.Add(shortname, new List<ulong>());
							
							if(addOrRemove)
							{
								if(!StoredDataSkins[shortname].Contains(skinID) && !_adminAndVipSkins.Contains(skinID))
								{
									if(config.Setting.UseImageLibrary) CheckSkinIDImageLibrary(skinID, publishedFileDetails.preview_url + _partURL);
									
									StoredDataSkinsName[skinID] = GetCleanSkinName(publishedFileDetails.title);
									StoredDataSkins[shortname].Add(skinID);
									count++;
								}
							}
							else
								if(StoredDataSkins[shortname].Contains(skinID))
								{
									StoredDataSkins[shortname].Remove(skinID);
									count++;
								}
						}
					}
			
			return count;
		}
		private const string permKitsD = "xskinmenu.defaultkits";
		
		private void LoadData(BasePlayer player)
		{
			ulong userID = player.userID;
			
			if(Interface.Oxide.DataFileSystem.ExistsDatafile($"XDataSystem/XSkinMenu/UserSettings/{userID}"))
			{
				var Data = Interface.Oxide.DataFileSystem.ReadObject<Data>($"XDataSystem/XSkinMenu/UserSettings/{userID}");
				
				StoredData[userID] = Data ?? DATA();
			}
			else
				StoredData[userID] = DATA();
			
			if(!StoredDataFriends.ContainsKey(userID))
                StoredDataFriends.Add(userID, config.PSetting.ChangeF);
			
			var list = StoredData[userID].Skins;
			
			foreach(var skin in StoredDataSkins)
			{
				string key = skin.Key;
				
				if(!list.ContainsKey(key))
					list.Add(key, _items.ContainsKey(key) ? _items[key] : 0);
			}
			
			//FP_TOS
			if(config.Setting.FacepunchTOS)
			{
				foreach(var kit in StoredData[userID].Kits)
				{
					var items = kit.Value;
					
					foreach(var item in items.Keys.ToList())
						if(_allFPSkins.Contains(items[item]))
							items[item] = 0;
				}
				
				foreach(var item in list.Keys.ToList())
					if(_allFPSkins.Contains(list[item]))
						list[item] = 0;
			}
			
			///DEL
			
			foreach(var kits in StoredData[userID].Kits)
				kits.Value.Remove("wallpaper");
			
			///DEL
		}
		
		[ConsoleCommand("xskin_import_file")]
		private void ccmdImportFile(ConsoleSystem.Arg args)
		{
			if(args?.Args != null && args.Args.Length >= 1 && args.Player() == null)
			{
				string fileName = args.GetString(0, "Skins001");
				
				if(Interface.Oxide.DataFileSystem.ExistsDatafile($"XDataSystem/XSkinMenu/{fileName}"))
				{
					Dictionary<string, List<ulong>> skinsFile = null;
					
					try
					{
						skinsFile = Interface.Oxide.DataFileSystem.ReadObject<Dictionary<string, List<ulong>>>($"XDataSystem/XSkinMenu/{fileName}");
					}
					catch(Exception ex)
					{
						PrintError(LanguageEnglish ? $"File reading error [ {fileName} ] !!!" : $"Ошибка чтений файла [ {fileName} ] !!!");
						
						return;
					}
					
					if(skinsFile != null)
					{
						int count = 0;
						
						foreach(var item in skinsFile)
						{
							if(StoredDataSkins.TryGetValue(item.Key, out List<ulong> skins))
							{
								foreach(ulong skin in item.Value)
									if(!skins.Contains(skin) && !_adminAndVipSkins.Contains(skin) && !Blacklisted(skin))
									{
										skins.Add(skin);
										count++;
									}
							}
							else
							{
								skins = new List<ulong>(item.Value);
								skins.RemoveAll(skin => _adminAndVipSkins.Contains(skin));
								skins.RemoveAll(skin => Blacklisted(skin));
								
								StoredDataSkins.Add(item.Key, skins);
								count += skins.Count;
							}
						}
						
						SaveDataSkins();
						PrintWarning(LanguageEnglish ? $"File successfully read! [ {count} ] new skins added!" : $"Файл успешно прочитан! Было добавлено [ {count} ] новых скинов!");
					}
					else
						PrintError(LanguageEnglish ? $"File reading error [ {fileName} ] !!!" : $"Ошибка чтений файла [ {fileName} ] !!!");
				}
				else
					PrintError(LanguageEnglish ? $"File [ {fileName} ] not found!" : $"Файл [ {fileName} ] не найден!");
			}
		}
		private const string permAdminS = "xskinmenu.adminskins";
		
		private class WallpaperSide
		{
			public string Side2;
			
			public WallpaperSide(string side, string side2)
			{
				Side = side; Side2 = side2;
			}
			public string Side;
		}
		
		private void SaveDataSkinsName()
		{
			if(StoredDataSkinsName != null && StoredDataSkinsName.Count != 0)
				Interface.Oxide.DataFileSystem.WriteObject("XDataSystem/XSkinMenu/SkinsName", StoredDataSkinsName);
		}
		
		private void SkinNameRequest(int code, string response)
		{
			if(code == 200 && response != null)
			{
				SkinsQueryResponse sQR = JsonConvert.DeserializeObject<SkinsQueryResponse>(response);
				
				if(sQR?.response != null && sQR.response.publishedfiledetails != null && sQR.response.publishedfiledetails.Length != 0)
				{
					PublishedFileDetails publishedFileDetails = sQR.response.publishedfiledetails[0];
					
					ulong skinID = Convert.ToUInt64(publishedFileDetails.publishedfileid);
						
					if(config.Setting.UseImageLibrary)
						CheckSkinIDImageLibrary(skinID, publishedFileDetails.preview_url + _partURL);
						
					StoredDataSkinsName[skinID] = GetCleanSkinName(publishedFileDetails.title);
					SaveDataSkinsName();
				}
			}
		}
		
		private void CollectionRequest(int code, string response, ulong collectionID)
		{
			if(code == 200 && response != null)
			{
				CollectionQueryResponse cQR = JsonConvert.DeserializeObject<CollectionQueryResponse>(response);
				
				if(cQR?.response == null || cQR.response.resultcount == 0 || cQR.response.collectiondetails == null || cQR.response.collectiondetails.Length == 0 || cQR.response.collectiondetails[0].result != 1)
				{
					PrintError(LanguageEnglish ? ">>> ERROR ADDING/REMOVING COLLECTION <<<" : ">>> ОШИБКА ПРИ ДОБАВЛЕНИИ/УДАЛЕНИИ КОЛЛЕКЦИИ <<<");
					_coroutineListCollections.Remove(collectionID);
					
					return;
				}
				
				if(_workshopSkins.ContainsKey(collectionID))
					_workshopSkins[collectionID].Clear();
				else
					_workshopSkins.Add(collectionID, new List<ulong>());
				
				
				if(_workshopCollections.ContainsKey(collectionID))
					_workshopCollections[collectionID].Clear();
				else
					_workshopCollections.Add(collectionID, new List<ulong>());
				
				foreach(CollectionChild child in cQR.response.collectiondetails[0].children)
					switch(child.filetype)
					{
						case 1:
						{
							_workshopSkins[collectionID].Add(Convert.ToUInt64(child.publishedfileid));
							
							break;
						}
						case 2:
						{
							_workshopCollections[collectionID].Add(Convert.ToUInt64(child.publishedfileid));
							
							break;
						}
					}
					
				SkinsWorkshopRequest(collectionID);
			}
			else
			{
				PrintError(LanguageEnglish ? ">>> ERROR ADDING/REMOVING COLLECTION <<<" : ">>> ОШИБКА ПРИ ДОБАВЛЕНИИ/УДАЛЕНИИ КОЛЛЕКЦИИ <<<");
				_coroutineListCollections.Remove(collectionID);
			}
		}
		
		[ConsoleCommand("skin_s")]
		private void ccmdSetting(ConsoleSystem.Arg args)
		{
			BasePlayer player = args.Player();
			
			if(!permission.UserHasPermission(player.UserIDString, permSetting)) 
			{
				SendReply(player, lang.GetMessage("NOPERM", this, player.UserIDString));
				return;
			}
			
			if(Cooldowns.ContainsKey(player))
                if(Cooldowns[player].Subtract(DateTime.Now).TotalSeconds >= 0) return;
			
			Effect x = new Effect("assets/bundled/prefabs/fx/notice/loot.drag.grab.fx.prefab", player, 0, new Vector3(), new Vector3());
			
			switch(args.GetString(0))
			{
				case "open":
				{
					CategoryGUI(player, -1);
					SettingGUI(player);
					break;
				}
				case "inventory":
				{
					StoredData[player.userID].ChangeSI = !StoredData[player.userID].ChangeSI;
					SettingGUI(player);
					break;
				}
				case "clear":
				{
					StoredData[player.userID].ChangeSCL = !StoredData[player.userID].ChangeSCL;
					SettingGUI(player);
					break;
				}
				case "entity":
				{
					StoredData[player.userID].ChangeSE = !StoredData[player.userID].ChangeSE;
					SettingGUI(player);
					break;
				}
				case "friends":
				{
					StoredDataFriends[player.userID] = !StoredDataFriends[player.userID];
					SettingGUI(player);
					break;
				}
				case "give":
				{
					StoredData[player.userID].ChangeSG = !StoredData[player.userID].ChangeSG;
					StoredData[player.userID].ChangeSP = false;
					SettingGUI(player);
					break;
				}				
				case "pickup":
				{
					StoredData[player.userID].ChangeSP = !StoredData[player.userID].ChangeSP;
					StoredData[player.userID].ChangeSG = false;
					SettingGUI(player);
					break;
				}				
				case "giveno":
				{
					StoredData[player.userID].ChangeSGN = !StoredData[player.userID].ChangeSGN;
					SettingGUI(player);
					break;
				}
				case "craft":
				{
					StoredData[player.userID].ChangeSC = !StoredData[player.userID].ChangeSC;
					SettingGUI(player);
					break;
				}
				case "spraycan":
				{
					StoredData[player.userID].UseSprayC = !StoredData[player.userID].UseSprayC;
					SettingGUI(player);
					break;
				}
				case "sound":
				{
					StoredData[player.userID].UseSoundE = !StoredData[player.userID].UseSoundE;
					SettingGUI(player);
					break;
				}
				case "defaultmenu":
				{
					StoredData[player.userID].Comfort = false;
					StoredData[player.userID].ComfortP = false;
					SettingGUI(player);
					break;
				}				
				case "comfortmenu":
				{
					StoredData[player.userID].Comfort = true;
					SettingGUI(player);
					break;
				}				
				case "comfortplusmenu":
				{
					if(config.GUI.ButtonComfortPlus)
					{
						if(StoredData[player.userID].ComfortP)
							StoredData[player.userID].ComfortP = false;
						else
						{
							StoredData[player.userID].Comfort = true;
							StoredData[player.userID].ComfortP = true;
						}
						
						SettingGUI(player);
					}
					break;
				}
			}
			
			if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
			Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
		}
		private readonly Dictionary<string, Dictionary<ulong, string>> ersK = new Dictionary<string, Dictionary<ulong, string>>
		{
			["pistol.semiauto"] = new Dictionary<ulong, string> { [10330] = "pistol.semiauto.a.m15" },
			["shotgun.double"] = new Dictionary<ulong, string> { [13086] = "blunderbuss" },
			["shotgun.pump"] = new Dictionary<ulong, string> { [10420] = "krieg.shotgun" },
			["bow.hunting"] = new Dictionary<ulong, string> { [10231] = "legacy bow" },
			["crossbow"] = new Dictionary<ulong, string> { [10492] = "crossbowbowless" },
			["rifle.ak"] = new Dictionary<ulong, string> { [13070] = "rifle.ak.ice", [13076] = "rifle.ak.diver", [10292] = "rifle.ak.med", [10308] = "rifle.ak.jungle", [10560] = "rifle.ak.glass", [10561] = "rifle.ak.glass.red", [10562] = "rifle.ak.glass.blue", [10563] = "rifle.ak.glass.green", [10564] = "rifle.ak.glass.pink" },
			["rifle.lr300"] = new Dictionary<ulong, string> { [10431] = "rifle.lr300.space" },
			["rocket.launcher"] = new Dictionary<ulong, string> { [10236] = "rocket.launcher.dragon", [10337] = "rocket.launcher.rpg7" },
			["mace"] = new Dictionary<ulong, string> { [10214] = "mace.baseballbat" },
			["knife.combat"] = new Dictionary<ulong, string> { [10306] = "sunken.knife" },
			["knife.bone"] = new Dictionary<ulong, string> { [10307] = "knife.bone.obsidian" },
			["spear.wooden"] = new Dictionary<ulong, string> { [10235] = "spear.cny" },
			["wall.frame.garagedoor"] = new Dictionary<ulong, string> { [10482] = "industrial.garagedoor" },
			["door.double.hinged.metal"] = new Dictionary<ulong, string> { [10290] = "medieval.door.double.hinged.metal" },
			["door.hinged.metal"] = new Dictionary<ulong, string> { [10189] = "door.hinged.industrial.a", [10198] = "factorydoor", [10291] = "medieval.door.hinged.metal" },
			["cupboard.tool"] = new Dictionary<ulong, string> { [10238] = "cupboard.tool.retro", [10239] = "cupboard.tool.shockbyte" },
			["wall.frame.lunar2025_a"] = new Dictionary<ulong, string> { [10281] = "wall.frame.lunar2025_b", [10282] = "wall.frame.lunar2025_c" },
			["gates.external.high.stone"] = new Dictionary<ulong, string> { [10299] = "gates.external.high.adobe" },
			["wall.external.high.stone"] = new Dictionary<ulong, string> { [10303] = "wall.external.high.adobe" },
			["gates.external.high.wood"] = new Dictionary<ulong, string> { [10301] = "gates.external.high.legacy" },
			["wall.external.high"] = new Dictionary<ulong, string> { [10316] = "wall.external.high.legacy" },
			["lock.code"] = new Dictionary<ulong, string> { [10318] = "lock.code.a.pilot" },
			["wall.window.glass.reinforced"] = new Dictionary<ulong, string> { [10343] = "window.paintable" },
			["autoturret"] = new Dictionary<ulong, string> { [10479] = "industrial.autoturret" },
			["guntrap"] = new Dictionary<ulong, string> { [10510] = "gamesroom.shotgun.trap" },
			["furnace"] = new Dictionary<ulong, string> { [10229] = "legacyfurnace" },
			["electric.furnace"] = new Dictionary<ulong, string> { [10481] = "industrial.electric.furnace" },
			["furnace.large"] = new Dictionary<ulong, string> { [10478] = "industrial.furnace.large" },
			["chair"] = new Dictionary<ulong, string> { [10215] = "chair.icethrone", [10310] = "chair.ejector.seat", [10327] = "beanbagseatfabric", [10332] = "beanbagseatleather" },
			["rockingchair"] = new Dictionary<ulong, string> { [13083] = "rockingchair.rockingchair3", [13084] = "rockingchair.rockingchair2" },
			["box.wooden.large"] = new Dictionary<ulong, string> { [10293] = "medieval.box.wooden.large", [10436] = "component.box.ammo.large", [10437] = "component.box.armor.large", [10438] = "component.box.charcoal.large", [10439] = "component.box.clothing.large", [10440] = "component.box.comps.large", [10441] = "component.box.explosives.large", [10442] = "component.box.food.large", [10443] = "component.box.guns.large", [10444] = "component.box.meds.large", [10445] = "component.box.metal.large", [10446] = "component.box.ore.large", [10447] = "component.box.scrap.large", [10448] = "component.box.stone.large", [10449] = "component.box.sulfur.large", [10450] = "component.box.tools.large", [10451] = "component.box.wood.large" },
			["box.wooden"] = new Dictionary<ulong, string> { [10329] = "pilot.hazmat.box.wooden" },
			["computerstation"] = new Dictionary<ulong, string> { [10228] = "twitchrivals2023desk" },
			["target.reactive"] = new Dictionary<ulong, string> { [10344] = "paintabletarget.reactive" },
			["sled"] = new Dictionary<ulong, string> { [13056] = "sled.xmas" },
			["discofloor"] = new Dictionary<ulong, string> { [13057] = "discofloor.largetiles" },
			["innertube"] = new Dictionary<ulong, string> { [13029] = "innertube.horse", [13031] = "innertube.unicorn" },
			["skull.trophy"] = new Dictionary<ulong, string> { [13052] = "skull.trophy.jar", [13053] = "skull.trophy.jar2", [13054] = "skull.trophy.table" },
			["skullspikes"] = new Dictionary<ulong, string> { [13050] = "skullspikes.candles", [13051] = "skullspikes.pumpkin" },
			["skylantern"] = new Dictionary<ulong, string> { [13058] = "skylantern.skylantern.green", [13059] = "skylantern.skylantern.purple", [13067] = "skylantern.skylantern.orange", [13069] = "skylantern.skylantern.red" },
			["ceilinglight"] = new Dictionary<ulong, string> { [10334] = "electric.fluorescentlight.ceiling" },
			["industrial.wall.light"] = new Dictionary<ulong, string> { [10288] = "industrial.wall.light.red", [10289] = "industrial.wall.light.green", [10309] = "industrial.wall.light.blue" },
			["wantedposter"] = new Dictionary<ulong, string> { [13080] = "wantedposter.wantedposter2", [13081] = "wantedposter.wantedposter3", [13082] = "wantedposter.wantedposter4" },
			["planter.large"] = new Dictionary<ulong, string> { [10298] = "rail.road.planter" },
			["planter.triangle"] = new Dictionary<ulong, string> { [10297] = "triangle.rail.road.planter" },
			["shelves"] = new Dictionary<ulong, string> { [10314] = "salvaged.bamboo.shelves", [10476] = "salvaged.industrial.shelves" },
			["metal.facemask"] = new Dictionary<ulong, string> { [10212] = "metal.facemask.hockey", [10217] = "metal.facemask.icemask" },
			["coffeecan.helmet"] = new Dictionary<ulong, string> { [10286] = "knightsarmour.helmet" },
			["sunglasses"] = new Dictionary<ulong, string> { [13040] = "sunglasses02black", [13041] = "sunglasses02camo", [13042] = "sunglasses02red", [13043] = "sunglasses03black", [13044] = "sunglasses03chrome", [13045] = "sunglasses03gold" },
			["hat.candle"] = new Dictionary<ulong, string> { [10339] = "hat.candle.birthday" },
			["hat.wolf"] = new Dictionary<ulong, string> { [10161] = "hat.dragonmask", [10173] = "hat.ratmask", [10179] = "attire.nesthat", [10192] = "hat.oxmask", [10205] = "hat.tigermask", [10218] = "hat.rabbitmask", [10284] = "hat.snakemask", [10356] = "hat.horsemask" },
			["metal.plate.torso"] = new Dictionary<ulong, string> { [10216] = "metal.plate.torso.icevest" },
			["roadsign.jacket"] = new Dictionary<ulong, string> { [10287] = "knighttorso.armour" },
			["roadsign.kilt"] = new Dictionary<ulong, string> { [10285] = "knightsarmour.skirt" },
			["hazmatsuit"] = new Dictionary<ulong, string> { [10180] = "hazmatsuit.spacesuit", [10201] = "hazmatsuit.nomadsuit", [10207] = "hazmatsuit.arcticsuit", [10211] = "hazmatsuit.lumberjack", [10222] = "hazmatsuit.diver", [10240] = "hazmatsuit.frontier", [10331] = "hazmatsuit.pilot", [10338] = "hazmatsuit.kick", [10419] = "hazmat.krieg" },
			["largebackpack"] = new Dictionary<ulong, string> { [10416] = "kriegbackpack" },
			["wood.armor.helmet"] = new Dictionary<ulong, string> { [10319] = "cocoknight.armor.helmet" },
			["wood.armor.jacket"] = new Dictionary<ulong, string> { [10320] = "cocoknight.armor.torso" },
			["wood.armor.pants"] = new Dictionary<ulong, string> { [10321] = "cocoknight.armor.pants" },
			["woodarmor.gloves"] = new Dictionary<ulong, string> { [10317] = "cocoknight.armor.gloves" },
			["horse.armor.roadsign"] = new Dictionary<ulong, string> { [10357] = "horse.armor.lny26" },
			["pickaxe"] = new Dictionary<ulong, string> { [13072] = "lumberjack.pickaxe", [13078] = "diverpickaxe" },
			["stone.pickaxe"] = new Dictionary<ulong, string> { [13074] = "concretepickaxe" },
			["rock"] = new Dictionary<ulong, string> { [10197] = "skull", [10315] = "jungle.rock" },
			["chainsaw"] = new Dictionary<ulong, string> { [10417] = "krieg.chainsword" },
			["hatchet"] = new Dictionary<ulong, string> { [13073] = "lumberjack.hatchet", [13077] = "diverhatchet", [13085] = "frontier_hatchet" },
			["stonehatchet"] = new Dictionary<ulong, string> { [13075] = "concretehatchet" },
			["torch"] = new Dictionary<ulong, string> { [10213] = "torch.torch.skull", [10480] = "industrial.torch", [13079] = "divertorch" }
		};
		
				
				
		private void SendInfo(BasePlayer player, string message)
        {
			CuiElementContainer container = new CuiElementContainer();
			
			container.Add(new CuiLabel
            {
				FadeOut = 0.5f,
                RectTransform = { AnchorMin = "0 0", AnchorMax = "0 0", OffsetMin = "3 3", OffsetMax = config.GUI.Page ? "315.5 33.75" : "371 33.75" },
                Text = { FadeIn = 0.5f, Text = message, Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 11, Color = "0.75 0.75 0.75 1" }
            }, ".SetItemB", ".SI", ".SI");
			
			CuiHelper.AddUi(player, container);
			player.Invoke(() => CuiHelper.DestroyUi(player, ".SI"), 5);
        }
		
		private Dictionary<BasePlayer, DateTime> Cooldowns = new Dictionary<BasePlayer, DateTime>();
		
		[ConsoleCommand("skin_c")]
		private void ccmdCategoryS(ConsoleSystem.Arg args)
		{
			BasePlayer player = args.Player();
			
			if(!permission.UserHasPermission(player.UserIDString, permUse)) 
			{
				SendReply(player, lang.GetMessage("NOPERM", this, player.UserIDString));
				return;
			}
			
			if(Cooldowns.ContainsKey(player))
                if(Cooldowns[player].Subtract(DateTime.Now).TotalSeconds >= 0) return;
			
			Effect x = new Effect("assets/bundled/prefabs/fx/notice/loot.drag.grab.fx.prefab", player, 0, new Vector3(), new Vector3());
			Effect z = new Effect("assets/bundled/prefabs/fx/weapons/survey_charge/survey_charge_stick.prefab", player, 0, new Vector3(), new Vector3());
			Effect y = new Effect("assets/prefabs/deployable/repair bench/effects/skinchange_spraypaint.prefab", player, 0, new Vector3(), new Vector3());
			
			switch(args.GetString(0))
			{
				case "category":
				{
					CategoryGUI(player, args.GetInt(2));
					ItemGUI(player, args.GetString(1));
					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
					
					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
					break;
				}
				case "skin":
				{
					if(StoredData[player.userID].Comfort)
					{
						string category = args.GetString(2), item = args.GetString(1);
						int page = args.GetInt(3);
						
						ItemGUI(player, category, page, item);
						SkinGUI(player, item, 0, category, page);
					}
					else
						SkinGUI(player, args.GetString(1));
					
					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
					
					if(!StoredData[player.userID].Comfort) CuiHelper.DestroyUi(player, ".ItemGUI");
					
					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
					break;
				}
				case "searchskin":
				{
					if(StoredData[player.userID].Comfort)
					{
						if(args.Args.Length >= 5)
							SkinGUI(player, args.GetString(1), 0, args.GetString(2), args.GetInt(3), string.Join(" ", args.Args.Skip(4)).ToLower());
						else if(args.Args.Length >= 4)
							SkinGUI(player, args.GetString(1), 0, args.GetString(2), args.GetInt(3));
					}
					else
					{
						if(args.Args.Length >= 4)
							SkinGUI(player, args.GetString(1), 0, args.GetString(2), 0, string.Join(" ", args.Args.Skip(3)).ToLower());
						else if(args.Args.Length >= 3)
							SkinGUI(player, args.GetString(1), 0, args.GetString(2), 0);
					}
					
					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
					
					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
					
					break;
				}
				case "searchskinmini":
				{
					if(args.Args.Length >= 4)
						SetItemGUI(player, args.GetString(1), 0, args.GetBool(2), string.Join(" ", args.Args.Skip(3)).ToLower());
					else if(args.Args.Length >= 3)
						SetItemGUI(player, args.GetString(1), 0, args.GetBool(2));
					
					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
					
					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
					
					break;
				}
				case "openkit":
				{
					if(config.Setting.EnableDefaultKits || config.Setting.EnableCustomKits)
					{
						CategoryGUI(player, -1);
						SkinKitsGUI(player);
					}
					break;
				}
				case "setskinentity":
				{
					if(player.CanBuild() && (StoredData[player.userID].UseSprayC && permission.UserHasPermission(player.UserIDString, permSprayC) || StoredData[player.userID].ChangeSE && permission.UserHasPermission(player.UserIDString, permEntity)))
					{
						var entity = GetRHitEntity(player);
						
						if(entity == null) return;
						
						if(!(entity is BaseVehicle))
						{
							string item = args.GetString(1);
							ulong skin = args.GetULong(2);
							
							if(entity.OwnerID == player.userID || player.currentTeam != 0 && player.Team.members.Contains(entity.OwnerID) && StoredDataFriends.ContainsKey(entity.OwnerID) && StoredDataFriends[entity.OwnerID])
							{
								if(_adminSkins.Contains(skin) && !permission.UserHasPermission(player.UserIDString, permAdminS)) return;
								if(_vipSkins.Contains(skin) && !permission.UserHasPermission(player.UserIDString, permVipS)) return;
								
								if(entity is BuildingBlock block)
								{
									if(block.HasWallpaper() && _blocksWallpapersShortnames.TryGetValue(block.ShortPrefabName, out WallpaperSide wallpaperShortname))
									{
										if(block.HasWallpaper(0) && block.wallpaperID != skin && block.CanSeeWallpaperSocket(player, 0))
										{
											if(wallpaperShortname.Side == item && StoredDataSkins.ContainsKey(wallpaperShortname.Side) && (StoredDataSkins[wallpaperShortname.Side].Contains(skin) || _adminAndVipSkins.Contains(skin) || skin == 0))
											{
												block.SetWallpaper(skin, 0, block.wallpaperRotation);
												
												Effect.server.Run("assets/prefabs/deployable/repair bench/effects/skinchange_spraypaint.prefab", entity.transform.localPosition);
											}
										}
										else if(block.HasWallpaper(1) && block.wallpaperID2 != skin && block.CanSeeWallpaperSocket(player, 1))
										{
											if(wallpaperShortname.Side2 == item && StoredDataSkins.ContainsKey(wallpaperShortname.Side2) && (StoredDataSkins[wallpaperShortname.Side2].Contains(skin) || _adminAndVipSkins.Contains(skin) || skin == 0))
											{
												block.SetWallpaper(skin, 1, block.wallpaperRotation2);
												
												Effect.server.Run("assets/prefabs/deployable/repair bench/effects/skinchange_spraypaint.prefab", entity.transform.localPosition);
											}
										}
									}
								}
								else if(_shortnamesEntity.ContainsKey(entity.ShortPrefabName))
								{
									if(Blacklisted(entity.skinID))
									{
										EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
										return;
									}
									
									string shortname = _shortnamesEntity[entity.ShortPrefabName];
									
									if(shortname == item && StoredDataSkins.ContainsKey(shortname) && (StoredDataSkins[shortname].Contains(skin) || _adminAndVipSkins.Contains(skin) || skin == 0))
										SetSkinEntity(player, entity, shortname, skin);
								}
							}
						}
					}
					
					Cooldowns[player] = DateTime.Now.AddSeconds(1.5f);
					
					break;
				}
				case "setskinitem":
				{
					Item item = player.GetActiveItem();
					
					if(item != null && permission.UserHasPermission(player.UserIDString, permSkinI))
					{
						string shortname = _redirectSkins.ContainsKey(item.info.shortname) ? _redirectSkins[item.info.shortname] : item.info.shortname, sitem = args.GetString(1);
						ulong skin = args.GetULong(2);
						
						if(Blacklisted(item.skin))
						{
							EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
							Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
							return;
						}
						
						if(_adminSkins.Contains(skin) && !permission.UserHasPermission(player.UserIDString, permAdminS)) return;
						if(_vipSkins.Contains(skin) && !permission.UserHasPermission(player.UserIDString, permVipS)) return;
						
						if(shortname == sitem && StoredDataSkins.ContainsKey(shortname) && (StoredDataSkins[shortname].Contains(skin) || _adminAndVipSkins.Contains(skin) || skin == 0))
						{
							if(StoredData[player.userID].ChangeSG)
							{
								StoredData[player.userID].ChangeSG = false;
								SSI(player, item, skin, shortname, false, config.Setting.ReissueActiveItem);
								StoredData[player.userID].ChangeSG = true;
							}
							else
								SSI(player, item, skin, shortname, false, config.Setting.ReissueActiveItem);
							
							EffectNetwork.Send(y, player.Connection);
						}
					}
					
					Cooldowns[player] = DateTime.Now.AddSeconds(1.5f); // Don't touch here!!!   |   Здесь не трогать!!! =)
					
					break;
				}
				case "setskin":
				{ 
					string item = args.GetString(1);
					ulong skin = args.GetULong(2);
					
					if(!permission.UserHasPermission(player.UserIDString, permSkinC) || !StoredData[player.userID].Skins.ContainsKey(item)) return;
					if(_adminSkins.Contains(skin) && !permission.UserHasPermission(player.UserIDString, permAdminS)) return;
					if(_vipSkins.Contains(skin) && !permission.UserHasPermission(player.UserIDString, permVipS)) return;
					if(!(StoredDataSkins[item].Contains(skin) || _adminAndVipSkins.Contains(skin))) return;
					
					StoredData[player.userID].Skins[item] = skin;
					
					if(!permission.UserHasPermission(player.UserIDString, permInv))
						SendReply(player, lang.GetMessage("NOPERM", this, player.UserIDString));
					else
					{
					    if(StoredData[player.userID].ChangeSI) SetSkinItem(player, item, skin);
						if(config.GUI.SkinUP)
						{
							if(StoredData[player.userID].Comfort)
							{
								string category = args.GetString(4);
								int page = args.GetInt(5);
								
								ItemGUI(player, category, page, item);
								
								if(args.Args.Length >= 7)
									SkinGUI(player, item, args.GetInt(3), category, page, string.Join(" ", args.Args.Skip(6)).ToLower());
								else
									SkinGUI(player, item, args.GetInt(3), category, page);
							}
							else
							{
								if(args.Args.Length >= 5)
									SkinGUI(player, item, args.GetInt(3), "null", 0, string.Join(" ", args.Args.Skip(4)).ToLower());
								else
									SkinGUI(player, item, args.GetInt(3));
							}
						}
					}
					
					EffectNetwork.Send(y, player.Connection);
					
					Cooldowns[player] = DateTime.Now.AddSeconds(1.5f); // Don't touch here!!!   |   Здесь не трогать!!! =)
					break;
				}
				case "clear":
				{
					if(!permission.UserHasPermission(player.UserIDString, permSkinC)) return;
					
					string item = args.GetString(1);
					StoredData[player.userID].Skins[item] = 0;
					
					CuiHelper.DestroyUi(player, $".I + {args.GetString(2)}");
					if(StoredData[player.userID].ChangeSCL) SetSkinItem(player, item, 0);
					if(config.GUI.MainSkin) ItemGUI(player, args.GetString(3), args.GetInt(4), item);
					
					EffectNetwork.Send(z, player.Connection);
					
					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
					break;
				}
				case "clearall":
				{
					if(!permission.UserHasPermission(player.UserIDString, permSkinC)) return;
					
					StoredData[player.userID].Skins.Clear();
					
					foreach(var skin in StoredDataSkins) 
						StoredData[player.userID].Skins.Add(skin.Key, 0);
					
					GUI(player);
					EffectNetwork.Send(z, player.Connection);
					
					Cooldowns[player] = DateTime.Now.AddSeconds(2.5f);
					break;
				}
				case "adminf":
				{
					if(!permission.UserHasPermission(player.UserIDString, permAdmin)) return;
					
					if(_adminUiFD.Contains(player.userID))
						_adminUiFD.Remove(player.userID);
					else
						_adminUiFD.Add(player.userID);
					
					GUI(player);
					EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/weapons/landmine/landmine_trigger.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
					
					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
					break;
				}
				case "zoomskin":
				{
					ZoomGUI(player, args.GetInt(1), args.GetULong(2), args.GetBool(3));
					
					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
					
					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
					break;
				}
				case "clearkit":
				{
					if(!config.Setting.EnableCustomKits || !permission.UserHasPermission(player.UserIDString, permKitsC)) return;
					
					string key = args.GetString(1).Replace("'", "");
					int Page = args.GetInt(2);
					
					if(StoredData[player.userID].Kits.ContainsKey(key))
					{
						StoredData[player.userID].Kits.Remove(key);
							
						CustomKitsGUI(player, Page);
						EffectNetwork.Send(z, player.Connection);
					}
					
					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
					break;
				}
				case "kitinfo":
				{
					if(config.Setting.EnableDefaultKits || config.Setting.EnableCustomKits)
						KitInfoGUI(player, args.GetString(1), args.GetString(2).Replace("'", ""));
					
					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
					break;
				}
				case "createkitui":
				{
					CreateKitGUI(player);
					
					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
					break;
				}
				case "createkit":
				{
					if(!config.Setting.EnableCustomKits || !permission.UserHasPermission(player.UserIDString, permKitsC)) return;
					
					if(StoredData[player.userID].Kits.Count >= GetMaxCountKits(player))
					{
						SendReply(player, lang.GetMessage("MAX_KITS", this, player.UserIDString));
						return;
					}
					
					string kitname = string.Join(" ", args.Args.Select(sv => sv.ToString()).ToArray(), 1, args.Args.Length - 1);
					
					if(StoredData[player.userID].Kits.ContainsKey(kitname))
						SendReply(player, string.Format(lang.GetMessage("KIT_NAME_A_E", this, player.UserIDString), kitname));
					else
					{
						Dictionary<string, ulong> newkit = StoredData[player.userID].Skins.Where(xx => xx.Value != 0).ToDictionary(xxx => xxx.Key, xxx => xxx.Value);
						
						if(newkit.Count != 0)
						{
							StoredData[player.userID].Kits.Add(kitname, newkit);
							CustomKitsGUI(player);
						}
						else
							SendReply(player, lang.GetMessage("KIT_EMPTY", this, player.UserIDString));
					}
					
					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
					break;
				}
				case "setkit":
				{
					string category = args.GetString(1), kitname = args.GetString(4).Replace("'", "");
					bool invK = args.GetBool(2), setK = args.GetBool(3), permAS = !permission.UserHasPermission(player.UserIDString, permAdminS), permVS = !permission.UserHasPermission(player.UserIDString, permVipS);
					bool offChangeSG = false;
					
					Dictionary<string, ulong> skins = category == "default" && config.Setting.EnableDefaultKits && config.KitsSetting.ContainsKey(kitname) && permission.UserHasPermission(player.UserIDString, permKitsD) ? config.KitsSetting[kitname] : category == "custom" && config.Setting.EnableCustomKits && StoredData[player.userID].Kits.ContainsKey(kitname) && permission.UserHasPermission(player.UserIDString, permKitsC) ? StoredData[player.userID].Kits[kitname] : new Dictionary<string, ulong>();
					
					if(StoredData[player.userID].ChangeSG)
					{
						StoredData[player.userID].ChangeSG = false;
						offChangeSG = true;
					}
					
					foreach(var skin in skins)
					{
						if(_adminSkins.Contains(skin.Value) && permAS) continue;
						if(_vipSkins.Contains(skin.Value) && permVS) continue;
						//if(!(StoredDataSkins[skin.Key].Contains(skin.Value) || _adminAndVipSkins.Contains(skin.Value))) continue;
						
						if(setK && StoredData[player.userID].Skins.ContainsKey(skin.Key))
							StoredData[player.userID].Skins[skin.Key] = skin.Value;
						
						if(invK)
							SetSkinItem(player, skin.Key, skin.Value);
					}
					
					if(offChangeSG)
						StoredData[player.userID].ChangeSG = true;
					
					EffectNetwork.Send(y, player.Connection);
					
					Cooldowns[player] = DateTime.Now.AddSeconds(2.5f); // Don't touch here!!!   |   Здесь не трогать!!! =)
					break;
				}
			}
		}
		
		private void SetSkinCraftGive(BasePlayer player, Item item, bool isgive = false)
		{
			if(player == null || item == null) return;
			
			string shortname = item.info.shortname;

			if(!StoredData.TryGetValue(player.userID, out Data data) || !data.Skins.TryGetValue(shortname, out ulong skin)) return;
			if(config.Setting.ReskinConfig && !_items.ContainsKey(shortname)) return;
			if(item.skin == skin || Blacklisted(item.skin)) return;

			if(isgive && skin == 0 && data.ChangeSGN) return;
			
			SSI(player, item, skin, ersK.ContainsKey(shortname) ? shortname : "", isgive);
		}
		
		private void ItemGUI(BasePlayer player, string category, int Page = 0, string itemname = "null")
		{
			bool comfort = StoredData[player.userID].Comfort, comfortp = StoredData[player.userID].ComfortP;
			
			CuiHelper.DestroyUi(player, ".SettingGUI");
			if(!comfort) CuiHelper.DestroyUi(player, ".SkinGUI");
            CuiElementContainer container = new CuiElementContainer(); 
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = comfort ? "-502.5 82.5" : "-502.5 -228.25", OffsetMax = "502.5 177.5" },
                Image = { Color = "0 0 0 0" }
            }, ".SGUI", ".ItemGUI", ".ItemGUI");
			
						
			if(comfortp)
			{
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "-136.5 -5", OffsetMax = "136.5 5" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, ".ItemGUI", ".ComfortPlus");
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "5 5", OffsetMax = "-5 -5" },
					Image = { Color = config.GUI.BColor4 }
				}, ".ComfortPlus", ".Plus");
			}
			
						
			bool permadmins = permission.UserHasPermission(player.UserIDString, permAdminS), permvips = permission.UserHasPermission(player.UserIDString, permVipS);
			var player_data = StoredData[player.userID].Skins;
			
			int x = 0, y = 0, z = 0, count = comfortp ? 14 : 11;
			
			foreach(var item in comfort ? config.Category[category].Skip(Page * count).Take(count) : config.Category[category].Skip(Page * 40))
			{
				string key = item.Key;
				
				bool c = player_data.ContainsKey(key);
				ulong skinID = c ? player_data[key] : 0;
				bool s = skinID != 0, available = c && (StoredDataSkins.ContainsKey(key) && StoredDataSkins[key].Count != 0 || permvips && config.Setting.VipSkins.ContainsKey(key) && config.Setting.VipSkins[key].Count != 0 || permadmins && config.Setting.AdminSkins.ContainsKey(key) && config.Setting.AdminSkins[key].Count != 0);
				int itemid = _itemsId[key];
				
			    container.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = comfort ? $"{(comfortp ? -611.25 : -480) + (x * 87.5)} {-42.5 - (y * 90)}" : $"{-497.5 + (x * 100)} {102.375 - (y * 100)}", OffsetMax = comfort ? $"{(comfortp ? -526.25 : -395) + (x * 87.5)} {42.5 - (y * 90)}" : $"{-402.5 + (x * 100)} {197.375 - (y * 100)}" },
                    Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
                }, ".ItemGUI", ".Item");
				
					container.Add(new CuiElement 
					{
						Parent = ".Item",
						Components =
						{
							new CuiImageComponent { ItemId = itemid, SkinId = config.GUI.MainSkin ? skinID : 0, Color = available ? "1 1 1 1" : "1 1 1 0.3" },
							new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "7.5 7.5", OffsetMax = "-7.5 -7.5" }
						}
					});
				
				if(available)
				{
				    container.Add(new CuiButton
                    {
                        RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
                        Button = { Color = "0 0 0 0", Command = $"skin_c skin {key} {category} {Page}" },
                        Text = { Text = "" }
                    }, ".Item");				    
				
				    if(s)
				        container.Add(new CuiButton
                        {
                            RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-20 5", OffsetMax = "-5 20" },
                            Button = { Color = "1 1 1 0.75", Sprite = "assets/icons/clear.png", Command = $"skin_c clear {key} {z} {category} {Page}" },
                            Text = { Text = "" }
                        }, ".Item", $".I + {z}");
				}
				
				if(comfort)
					container.Add(new CuiPanel
					{
						RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMax = "0 1.5" },
						Image = { Color = key == itemname ? config.GUI.ActiveColor : config.GUI.InactiveColor, Material = "assets/icons/greyout.mat" }
					}, ".Item");
				
				x++;
				z++;
				
				if(!comfort && x == 10)
				{
					x = 0;
					y++;
					
					if(y == 4)
						break;
				}
			}
			
			bool back = Page != 0;
			
			if(comfort)
			{
				bool next = config.Category[category].Count > ((Page + 1) * count);
				
				container.Add(new CuiButton
				{    
					RectTransform = { AnchorMin = "0 0", AnchorMax = "0 1", OffsetMin = "5 5", OffsetMax = "17.5 -5" },
					Button = { Color = back ? config.GUI.ActiveBackColor : config.GUI.InactiveBackColor, Command = back ? $"page.xskinmenu item {category} {Page - 1} {itemname}" : "" },
					Text = { Text = "<", Align = TextAnchor.MiddleCenter, FontSize = 14, Font = "robotocondensed-regular.ttf", Color = back ? config.GUI.ActiveBackColorText : config.GUI.InactiveBackColorText }
				}, comfortp ? ".Plus" : ".ItemGUI");
				
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "1 0", AnchorMax = "1 1", OffsetMin = "-17.5 5", OffsetMax = "-5 -5" },
					Button = { Color = next ? config.GUI.ActiveNextReloadColor : config.GUI.InactiveNextReloadColor, Command = next ? $"page.xskinmenu item {category} {Page + 1} {itemname}" : "" },
					Text = { Text = ">", Align = TextAnchor.MiddleCenter, FontSize = 14, Font = "robotocondensed-regular.ttf", Color = next ? config.GUI.ActiveNextReloadColorText : config.GUI.InactiveNextReloadColorText }
				}, comfortp ? ".Plus" : ".ItemGUI");
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMin = "0 -5", OffsetMax = "0 0" },
					Image = { Color = config.GUI.BColor1, Material = "assets/content/ui/uibackgroundblur.mat" }
				}, ".ItemGUI");
			}
			else
			{
				bool next = config.Category[category].Count > ((Page + 1) * 40);
				
				container.Add(new CuiButton
				{    
					RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-245 -36.75" : "-190 -36.75", OffsetMax = config.GUI.Page ? "-155 -10" : "-100 -10" },
					Button = { Color = back ? config.GUI.ActiveBackColor : config.GUI.InactiveBackColor, Command = back ? $"page.xskinmenu item {category} {Page - 1}" : "" },
					Text = { Text = lang.GetMessage("BACK", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = back ? config.GUI.ActiveBackColorText : config.GUI.InactiveBackColorText }
				}, ".ItemGUI");
				
				if(config.GUI.Page)
					container.Add(new CuiLabel
					{
						RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-152.5 -36.75", OffsetMax = "-97.5 -10" },
						Text = { Text = $"{Page + 1}/{Math.Ceiling((double)config.Category[category].Count / 40)}", Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 15, Color = "0.75 0.75 0.75 0.9" }
					}, ".ItemGUI");
				
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-95 -36.75", OffsetMax = "-5 -10" },
					Button = { Color = next ? config.GUI.ActiveNextReloadColor : config.GUI.InactiveNextReloadColor, Command = next ? $"page.xskinmenu item {category} {Page + 1}" : "" },
					Text = { Text = lang.GetMessage("NEXT", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = next ? config.GUI.ActiveNextReloadColorText : config.GUI.InactiveNextReloadColorText }
				}, ".ItemGUI");
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-255 -41.75" : "-200 -41.75", OffsetMax = config.GUI.Page ? "-250 0" : "-195 0" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, ".ItemGUI");
			}
			
						
			if(permission.UserHasPermission(player.UserIDString, permPlayerAdd))
			{
				float side1 = 0, side2 = 0;
				
				if(config.GUI.Page) side1 -= 27.5f;
				if(config.GUI.ButtonClear) side1 += 100.0f;
				if(permission.UserHasPermission(player.UserIDString, permAdmin)) side1 += 20.875f;
				if(comfort)
				{
					side1 -= 77.5f;
					side2 -= 310.75f;
				}
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = $"{-271.5 + side1} {-36.75 + side2}", OffsetMax = $"{-102.5 + side1} {-10 + side2}" },
					Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
				}, ".ItemGUI", ".InputSkin");
				
				container.Add(new CuiElement
				{
					Parent = ".InputSkin",
					Components =
					{
						new CuiInputFieldComponent { Text = "", Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 10, Command = "xskin_player_add addskin", CharsLimit = 10, NeedsKeyboard = true, Color = "0.8 0.8 0.8 1" },
						new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "0 13.25", OffsetMax = "0 0" }
					}
				});
				
				container.Add(new CuiLabel
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMin = "0 1", OffsetMax = "0 13.25" },
					Text = { Text = lang.GetMessage("PLAYER_ADD_SKIN", this, player.UserIDString), Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 10, Color = "0.8 0.8 0.8 0.4" }
				}, ".InputSkin");
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = $"{-97.5 + side1} {-36.75 + side2}", OffsetMax = $"{71.5 + side1} {-10 + side2}" },
					Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
				}, ".ItemGUI", ".InputCollection");
				
				container.Add(new CuiElement
				{
					Parent = ".InputCollection",
					Components =
					{
						new CuiInputFieldComponent { Text = "", Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 10, Command = "xskin_player_add addcollection", CharsLimit = 10, NeedsKeyboard = true, Color = "0.8 0.8 0.8 1" },
						new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "0 13.5", OffsetMax = "0 0" }
					}
				});
				
				container.Add(new CuiLabel
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMin = "0 1", OffsetMax = "0 13.25" },
					Text = { Text = lang.GetMessage("PLAYER_ADD_COLLECTION", this, player.UserIDString), Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 10, Color = "0.8 0.8 0.8 0.4" }
				}, ".InputCollection");
			}
			
						
			CuiHelper.AddUi(player, container);
		}
		
		private HashSet<ulong> _adminSkins = new HashSet<ulong>();
		private HashSet<ulong> _blacklist = new HashSet<ulong>();
		
		private bool Blacklisted(ulong skinID)
		{
			List<ulong> source = config.Setting.Blacklist;
			
			if(_blacklist.Count != source.Count)
			{
				_blacklist.Clear();
				_blacklist.UnionWith(source);
			}
			
			return _blacklist.Contains(skinID);
		}
		
		private void cmdSetSkinEntity(BasePlayer player, string command, string[] args)
		{
			if(!permission.UserHasPermission(player.UserIDString, permEntity))
			{
				SendReply(player, lang.GetMessage("NOPERM", this, player.UserIDString));
				return;
			}
			
			if(StoredData[player.userID].ChangeSE)
				EntitySkin(player);
		}
		
		[ConsoleCommand("xskin_c")]
		private void ccmdCollection(ConsoleSystem.Arg args)
		{
			if(args?.Args != null && args.Args.Length >= 2)
				if(args.Player() == null || permission.UserHasPermission(args.Player().UserIDString, permAdmin))
				{
					if(args.GetString(0) == "addcollection" || args.GetString(0) == "removecollection")
					{
						ulong collectionID = args.GetULong(1);
						
						if(_coroutineListCollections.ContainsKey(collectionID))
							PrintError(LanguageEnglish ? $">>> COLLECTION [ {collectionID} ] ALREADY ADDED/REMOVED  <<<" : $">>> КОЛЛЕКЦИЯ [ {collectionID} ] УЖЕ ДОБАВЛЯЕТСЯ/УДАЛЯЕТСЯ <<<");
						else
						{
							if(args.GetString(0) == "addcollection") _addOrRemoveCollection[collectionID] = true;
							if(args.GetString(0) == "removecollection") _addOrRemoveCollection[collectionID] = false;
							
							_coroutineListCollections[collectionID] = null;
							webrequest.Enqueue("https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/", $"?key={config.Steam.APIKey}&collectioncount=1&publishedfileids[0]={collectionID}", (code, response) => CollectionRequest(code, response, collectionID), this, RequestMethod.POST);
						}
					}
				}
		}
		
		private void DefaultKitsGUI(BasePlayer player, int Page = 0)
		{
			bool eck = config.Setting.EnableCustomKits;
			CuiElementContainer container = new CuiElementContainer();
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = eck ? "0.5 1" : "1 1", OffsetMax = eck ? "-2.5 0" : "0 0" },
                Image = { Color = "0 0 0 0" }
            }, ".KitsGUI", ".DefaultKitsGUI", ".DefaultKitsGUI");
			
			container.Add(new CuiLabel
			{
				RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "0 -40.25", OffsetMax = "0 0" },
				Text = { Text = lang.GetMessage("DESC_DEFAULT_KITS", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 18, Font = "robotocondensed-regular.ttf", Color = "0.8 0.8 0.8 0.75" }
			}, ".DefaultKitsGUI");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "0 -45.25", OffsetMax = "0 -40.25" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".DefaultKitsGUI");
			
			if(permission.UserHasPermission(player.UserIDString, permKitsD))
			{
				int x = 0, y = 0, count = eck ? 6 : 12;
				
				foreach(var kit in config.KitsSetting.Skip(Page * count))
				{
					container.Add(new CuiPanel
					{
						RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = $"{(eck ? -245 : -492.5) + (x * 165)} {-7.5 - (y * 165)}", OffsetMax = $"{(eck ? -85 : -332.5) + (x * 165)} {152.5 - (y * 165)}" },
						Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
					}, ".DefaultKitsGUI", ".DKit");
					
					container.Add(new CuiLabel
					{
						RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "5 -20.5", OffsetMax = $"-5 -5" },
						Text = { Text = kit.Key.Replace(@"\", ""), Align = TextAnchor.MiddleLeft, FontSize = 11, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
					}, ".DKit");
					
					int xx = 0, yy = 0;
					
					foreach(var skin in kit.Value.Take(kit.Value.Count > 6 ? 5 : 6))
					{
						container.Add(new CuiPanel
						{
							RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = $"{-75 + (xx * 51.75)} {7 - (yy * 52.5)}", OffsetMax = $"{-28.25 + (xx * 51.75)} {54.5 - (yy * 52.5)}" },
							Image = { Color = config.GUI.BlockKitColor }
						}, ".DKit", ".Skin");
						
							container.Add(new CuiElement
							{
								Parent = ".Skin",
								Components =
								{
									new CuiImageComponent { ItemId = _itemsId[skin.Key], SkinId = skin.Value },
									new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "2.5 2.5", OffsetMax = "-2.5 -2.5" }
								}
							});		
							
						if(_adminSkins.Contains(skin.Value))
							container.Add(new CuiPanel
							{
								RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "1.5 -7.5", OffsetMax = "7.5 -1.5" },
								Image = { Color = "0.9 0 0 1", Sprite = "assets/icons/circle_closed.png" },
							}, ".Skin");
						else if(_vipSkins.Contains(skin.Value))
							container.Add(new CuiPanel
							{
								RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "1.5 -7.5", OffsetMax = "7.5 -1.5" },
								Image = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/circle_closed.png" },
							}, ".Skin");
						
						xx++;
						
						if(xx == 3)
						{
							xx = 0;
							yy++;
						}
					}
					
					if(kit.Value.Count > 6)
						container.Add(new CuiButton
						{
							RectTransform = { AnchorMin = "1 0.5", AnchorMax = "1 0.5", OffsetMin = "-51.75 -45.5", OffsetMax = "-5 2" },
							Button = { Color = config.GUI.BlockKitColor, Command = $"skin_c kitinfo default '{kit.Key}'" },
							Text = { Text = $"+{kit.Value.Count - 5}", Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
						}, ".DKit");
						
					container.Add(new CuiButton
					{
						RectTransform = { AnchorMin = "0 0", AnchorMax = "0.5 0", OffsetMin = "5 5", OffsetMax = "-26.75 24.25" },
						Button = { Color = config.GUI.BlockKitColor, Command = $"skin_c setkit default true false '{kit.Key}'" },
						Text = { Text = lang.GetMessage("INV", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 9, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
					}, ".DKit");
					
					container.Add(new CuiButton
					{
						RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = "-24.583 5", OffsetMax = "24.583 24.25" },
						Button = { Color = config.GUI.BlockKitColor, Command = $"skin_c setkit default false true '{kit.Key}'" },
						Text = { Text = lang.GetMessage("SET", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 9, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
					}, ".DKit");
					
					container.Add(new CuiButton
					{
						RectTransform = { AnchorMin = "0.5 0", AnchorMax = "1 0", OffsetMin = "26.75 5", OffsetMax = "-5 24.25" },
						Button = { Color = config.GUI.BlockKitColor, Command = $"skin_c setkit default true true '{kit.Key}'" },
						Text = { Text = lang.GetMessage("INV_SET", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 9, Font = "robotocondensed-regular.ttf", Color = "0.9 0.9 0.9 0.75" }
					}, ".DKit");
					
					x++;
					
					if(x == (eck ? 3 : 6))
					{
						x = 0;
						y++;
						
						if(y == 2)
							break;
					}
				}
				
				bool back = Page != 0;
				bool next = config.KitsSetting.Count > ((Page + 1) * count);
				
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = "-22.5 5", OffsetMax = "-2.5 25" },
					Button = { Color = back ? config.GUI.ActiveBackColor : config.GUI.InactiveBackColor, Command = back ? $"page.xskinmenu defaultkits 0 {Page - 1}" : "" },
					Text = { Text = "<", Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = back ? config.GUI.ActiveBackColorText : config.GUI.InactiveBackColorText }
				}, ".DefaultKitsGUI");
				
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = "2.5 5", OffsetMax = "22.5 25" },
					Button = { Color = next ? config.GUI.ActiveNextReloadColor : config.GUI.InactiveNextReloadColor, Command = next ? $"page.xskinmenu defaultkits 0 {Page + 1}" : "" },
					Text = { Text = ">", Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = next ? config.GUI.ActiveNextReloadColorText : config.GUI.InactiveNextReloadColorText }
				}, ".DefaultKitsGUI");
			}
			else
				container.Add(new CuiLabel
				{
					RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-220 -57.5", OffsetMax = "220 12.5" },
					Text = { Text = lang.GetMessage("DEFAULT_KITS_NOPERM", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 20, Color = "0.8 0.8 0.8 0.75060739" }
				}, ".DefaultKitsGUI");
			
			CuiHelper.AddUi(player, container);
		}
		
		private Dictionary<ulong, bool> _addOrRemoveCollection = new Dictionary<ulong, bool>();
		
		private void CanUseAutoKit(BasePlayer player)
		{
			if(config.Setting.ReskinRespawn)
				_removeATC.Add(player.userID);
		}
		
				
		[ConsoleCommand("xskin_player_add")]
		private void ccmdPlayerAdd(ConsoleSystem.Arg args)
		{
			if(args == null || args.Args == null) return;
			
			BasePlayer player = args.Player();
			
			if(player == null) return;
			
			if(CooldownsAdd.ContainsKey(player))
				if(CooldownsAdd[player].Subtract(DateTime.Now).TotalSeconds >= 0)
				{
					Player.Reply(player, lang.GetMessage("WAIT_ADD", this, player.UserIDString));
					
					return;
				}
			
			if(args.Args.Length >= 2 && permission.UserHasPermission(player.UserIDString, permPlayerAdd))
				switch(args.GetString(0))
				{
					case "addskin":
					{
						string msg = LanguageEnglish ? $"Player [ {player.displayName} | {player.userID} ] adds the skin [ {args.GetString(1)} ]." : $"Игрок [ {player.displayName} | {player.userID} ] добавляет скин [ {args.GetString(1)} ].";
						
						PrintWarning(msg);
						LogToFile("PlayerAddSkin", msg, this);
						
						AddRemoveSkins(new string[] { "", args.GetString(1) }, true);
						
						CooldownsAdd[player] = DateTime.Now.AddSeconds(5.0f);
						Player.Reply(player, lang.GetMessage("SUCCESSFUL_ADD", this, player.UserIDString));
						
						break;
					}
					case "addcollection":
					{
						ulong collectionID = args.GetULong(1);
						
						string msg = LanguageEnglish ? $"Player [ {player.displayName} | {player.userID} ] adds the collection [ {args.GetString(1)} | {collectionID} ]." : $"Игрок [ {player.displayName} | {player.userID} ] добавляет коллекцию [ {args.GetString(1)} | {collectionID} ].";
						
						PrintWarning(msg);
						LogToFile("PlayerAddCollection", msg, this);
						
						if(_coroutineListCollections.ContainsKey(collectionID))
							PrintError(LanguageEnglish ? $">>> COLLECTION [ {collectionID} ] ALREADY ADDED  <<<" : $">>> КОЛЛЕКЦИЯ [ {collectionID} ] УЖЕ ДОБАВЛЯЕТСЯ <<<");
						else
						{
							_addOrRemoveCollection[collectionID] = true;
							
							_coroutineListCollections[collectionID] = null;
							webrequest.Enqueue("https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/", $"?key={config.Steam.APIKey}&collectioncount=1&publishedfileids[0]={collectionID}", (code, response) => CollectionRequest(code, response, collectionID), this, RequestMethod.POST);
							
							Player.Reply(player, lang.GetMessage("SUCCESSFUL_ADD", this, player.UserIDString));
						}
						
						CooldownsAdd[player] = DateTime.Now.AddSeconds(5.0f);
						
						break;
					}
				}
		}
        protected override void SaveConfig() => Config.WriteObject(config);
		
		private void OnUserPermissionRevoked(string id, string permName)
		{
			BasePlayer player = BasePlayer.FindByID(ulong.Parse(id));
			
			if(player != null)
				ResetPlayerSkins(player);
		}
		   		 		  						  	   		   		 		  		 			   					  	 	 
        		
				
		private void AddToBlacklist(ulong skinID, string pluginName = "Unknown")
		{
			if(skinID != 0 && !Blacklisted(skinID))
			{
				PrintWarning(LanguageEnglish ? $"The [ {pluginName} ] plugin has blacklisted the skin - {skinID}" : $"Плагин [ {pluginName} ] добавил в черный список скин - {skinID}");
				
				config.Setting.Blacklist.Add(skinID);
				_blacklist.Add(skinID);
				SaveConfig();
			}
		}
		
		private void SkinKitsGUI(BasePlayer player, bool iscommand = false)
		{
			CuiHelper.DestroyUi(player, ".SkinGUI");
			CuiHelper.DestroyUi(player, ".ItemGUI");
			CuiHelper.DestroyUi(player, ".SettingGUI");
			CuiElementContainer container = new CuiElementContainer();
			
			if(iscommand)
			{
				CuiHelper.DestroyUi(player, BgMainLayer);
				
				if(config.GUI.BColor0 != "0 0 0 0" || config.GUI.CloseUI)
				{
					container.Add(new CuiPanel
					{
						CursorEnabled = true,
						RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
						Image = { Color = config.GUI.BColor0, Material = config.GUI.BMaterial0 }
					}, "Overlay", ".BgKitsBGUI", ".BgKitsBGUI");
					
					if(config.GUI.CloseUI)
						container.Add(new CuiButton
						{
							RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
							Button = { Color = "0 0 0 0", Close = ".BgKitsBGUI" },
							Text = { Text = "" }
						}, ".BgKitsBGUI");
						
					container.Add(new CuiPanel
					{
						RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-507.5 -187.875", OffsetMax = "507.5 227.875" },
						Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
					}, ".BgKitsBGUI", ".KitsBGUI");
				}
				else
					container.Add(new CuiPanel
					{
						CursorEnabled = true,
						RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-507.5 -187.875", OffsetMax = "507.5 227.875" },
						Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
					}, "Overlay", ".KitsBGUI", ".KitsBGUI");
				
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "5 5", OffsetMax = "-5 -5" },
					Image = { Color = config.GUI.BColor2 }
				}, ".KitsBGUI", ".KitsGUI");
				
				container.Add(new CuiButton
				{
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 0", OffsetMin = "-14.125 -38.25", OffsetMax = "14.125 -10" },
					Button = { Color = config.GUI.IconColor, Sprite = "assets/icons/close.png", Close = BgKitsLayer },
					Text = { Text = "" }
				}, ".KitsBGUI");
			}
			else
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "0 41.75", OffsetMax = "0 -92.5" },
					Image = { Color = "0 0 0 0" }
				}, ".SGUI", ".KitsGUI", ".KitsGUI");
			
			if(config.Setting.EnableDefaultKits && config.Setting.EnableCustomKits)
				container.Add(new CuiPanel
				{
					RectTransform = { AnchorMin = "0.5 0", AnchorMax = "0.5 1", OffsetMin = "-2.5 0", OffsetMax = "2.5 0" },
					Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
				}, ".KitsGUI");
			
			CuiHelper.AddUi(player, container);
			
			if(config.Setting.EnableDefaultKits) DefaultKitsGUI(player);
			if(config.Setting.EnableCustomKits) CustomKitsGUI(player);
		}
		
		private IEnumerator SkinsRequest(int code, string response, ulong collectionID)
		{
			PrintWarning(LanguageEnglish ? $">>> START LOADING COLLECTION [ {collectionID} ] <<<" : $">>> НАЧАЛА ЗАГРУЗКИ КОЛЛЕКЦИИ [ {collectionID} ] <<<");
			
			yield return CoroutineEx.waitForSeconds(2);
			
			if(code == 200 && response != null)
			{
				SkinsQueryResponse sQR = JsonConvert.DeserializeObject<SkinsQueryResponse>(response);
				
				if(sQR?.response == null || sQR.response.publishedfiledetails == null || sQR.response.publishedfiledetails.Length == 0)
					PrintError(LanguageEnglish ? ">>> ERROR ADDING/REMOVING COLLECTION <<<" : ">>> ОШИБКА ПРИ ДОБАВЛЕНИИ/УДАЛЕНИИ КОЛЛЕКЦИИ <<<");
				else
				{
					yield return CoroutineEx.waitForSeconds(2.0f);
					
					int count = SkinParse(sQR, _addOrRemoveCollection[collectionID]);
						
					yield return CoroutineEx.waitForSeconds(2.0f);	
					
					if(_addOrRemoveCollection[collectionID])
					{
						string msg = LanguageEnglish ? $"Successfully added {count}/{_workshopSkins[collectionID].Count} skins.  [ Collection - {collectionID} ]" : $"Успешно добавлено {count}/{_workshopSkins[collectionID].Count} скинов.  [ Коллекция - {collectionID} ]";
						
						if(_workshopCollections[collectionID].Count != 0)
						{
							msg += LanguageEnglish ? "\nRelated collections found" : "\nНайдены связанные коллекции:";
							
							foreach(ulong id in _workshopCollections[collectionID])
								msg += $"\n-  {id}";
						}
						
						PrintWarning(msg);
					}
					else
						PrintWarning(LanguageEnglish ? $"Successfully removed {count}/{_workshopSkins[collectionID].Count} skins.  [ Collection - {collectionID} ]" : $"Успешно удалено {count}/{_workshopSkins[collectionID].Count} скинов.  [ Коллекция - {collectionID} ]");
							
					SaveDataSkins();
					SaveDataSkinsName();
				}
			}
			else
				PrintError(LanguageEnglish ? ">>> ERROR ADDING/REMOVING COLLECTION <<<" : ">>> ОШИБКА ПРИ ДОБАВЛЕНИИ/УДАЛЕНИИ КОЛЛЕКЦИИ <<<");
			
			_coroutineListCollections.Remove(collectionID);
			yield return 0;
		}
		
		private void FinalizeAllSkinsRequest(HashSet<ulong> invalidSkins)
		{
			invalidSkins.RemoveWhere(skin => config.Steam.IgnoredCommunitySkinsList.Contains(skin));
			
			if(invalidSkins.Count != 0)
			{
				PrintError(LanguageEnglish ? $"[ {invalidSkins.Count} ] invalid skins have been automatically removed!" : $"[ {invalidSkins.Count} ] недействительных скинов были автоматически удалены!");
				
				CleanDictionary(StoredDataSkins, invalidSkins);
				CleanDictionary(config.Setting.AdminSkins, invalidSkins);
				CleanDictionary(config.Setting.VipSkins, invalidSkins);
				
				CleanDictionary2(config.KitsSetting, invalidSkins);
				CleanDictionary2(config.Category, invalidSkins);
				
				SaveDataSkins();
				SaveConfig();
				
				Interface.Oxide.DataFileSystem.WriteObject($"XDataSystem/XSkinMenu/AutomaticDeletionLogs/{DateTime.UtcNow.ToString("yyyy-MM-dd_HH-mm-ss")}", invalidSkins);
			}
			
			SaveDataSkinsName();
			
			timer.Once(5, () =>
			{
				if(config.Setting.UseImageLibrary && ImageLibrary && _coroutineDownloadCommunitySkinsImage == null)
					_coroutineDownloadCommunitySkinsImage = ServerMgr.Instance.StartCoroutine(DownloadCommunitySkinsImage());
			});
		}
		
		private void SkinGUI(BasePlayer player, string item, int Page = 0, string category = "null", int PageC = 0, string search = "")
		{
			bool comfort = StoredData[player.userID].Comfort;
			
            CuiElementContainer container = new CuiElementContainer();
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-502.5 -228.25", OffsetMax = comfort ? "502.5 77.5" : "502.5 177.5" },
                Image = { Color = "0 0 0 0" }
            }, ".SGUI", ".SkinGUI", ".SkinGUI");
			
			int x = 0, y = 0, count = comfort ? 30 : 40, yN = comfort ? 3 : 4;
			ulong s = StoredData[player.userID].Skins[item];
			int itemid = _itemsId[item];
			
			List<ulong> list_skins = new List<ulong>();
			
			if(config.Setting.AdminSkins.ContainsKey(item) && permission.UserHasPermission(player.UserIDString, permAdminS))
				list_skins.AddRange(config.Setting.AdminSkins[item]);
			if(config.Setting.VipSkins.ContainsKey(item) && permission.UserHasPermission(player.UserIDString, permVipS))
				list_skins.AddRange(config.Setting.VipSkins[item]);
			
			list_skins.AddRange(StoredDataSkins[item]);
			
			if(!string.IsNullOrEmpty(search))
			{
				if(ulong.TryParse(search, out ulong searchSkinID))
					list_skins = list_skins.Where(skinID => skinID == searchSkinID).ToList();
				else
					list_skins = list_skins.Where(skinID => StoredDataSkinsName.TryGetValue(skinID, out string name) && name.Contains(search, StringComparison.OrdinalIgnoreCase)).ToList();
			}
			
			foreach(ulong skin in list_skins.Skip(Page * count))
			{
				bool isAdminSkin = _adminSkins.Contains(skin), isVipSkin = _vipSkins.Contains(skin);
				
			    container.Add(new CuiPanel
                {
                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = comfort ? $"{-497.5 + (x * 100)} {52.375 - (y * 100)}" : $"{-497.5 + (x * 100)} {102.375 - (y * 100)}", OffsetMax = comfort ? $"{-402.5 + (x * 100)} {147.375 - (y * 100)}" : $"{-402.5 + (x * 100)} {197.375 - (y * 100)}" },
                    Image = { Color = s == skin ? config.GUI.ActiveBlockColor : config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
                }, ".SkinGUI", ".Skin");
				
					container.Add(new CuiElement
					{
						Parent = ".Skin",
						Components =
						{
							GetImageComponent(itemid, skin),
							new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "10 5", OffsetMax = "-10 -15" }
						}
					});		
					
				if(StoredDataSkinsName.ContainsKey(skin))
					container.Add(new CuiLabel
					{
						RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "2.5 -12.5", OffsetMax = "-2.5 -2.5" },
						Text = { Text = StoredDataSkinsName[skin], Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 8, Color = "0.85 0.85 0.85 1" }
					}, ".Skin");
				
				container.Add(new CuiButton
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
                    Button = { Color = "0 0 0 0", Command = comfort ? $"skin_c setskin {item} {skin} {Page} {category} {PageC} {search}" : $"skin_c setskin {item} {skin} {Page} {search}" },
                    Text = { Text = "" }
                }, ".Skin");
		   		 		  						  	   		   		 		  		 			   					  	 	 
				if(permission.UserHasPermission(player.UserIDString, permAdmin) && !_adminUiFD.Contains(player.userID))
				{
					container.Add(new CuiLabel
					{
						RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMin = "2.5 0.5", OffsetMax = "-2.5 10.5" },
						Text = { Text = $"{skin}", Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 8, Color = "0.85 0.85 0.85 1" }
					}, ".Skin");
					
					if(_adminAndVipSkins.Contains(skin))
						container.Add(new CuiButton
						{
							RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-17 -27", OffsetMax = "-5 -15" },
							Button = { Color = "1 1 1 0.75060739", Sprite = "assets/icons/rotate.png", Command = $"xskin refresh_ui {item} {skin} {Page} {(isAdminSkin ? "admin_to_default" : isVipSkin ? "vip_to_default" : "")} {category}" },
							Text = { Text = "" }
						}, ".Skin");
					else
					{
						container.Add(new CuiButton
						{
							RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-17 -27", OffsetMax = "-5 -15" },
							Button = { Color = "0.9 0 0 1", Sprite = "assets/icons/rotate.png", Command = $"xskin refresh_ui {item} {skin} {Page} default_to_admin {category}" },
							Text = { Text = "" }
						}, ".Skin");						
						
						container.Add(new CuiButton
						{
							RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-17 -42", OffsetMax = "-5 -30" },
							Button = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/rotate.png", Command = $"xskin refresh_ui {item} {skin} {Page} default_to_vip {category}" },
							Text = { Text = "" }
						}, ".Skin");
					}
					
				    container.Add(new CuiButton
                    {
                        RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-20 5", OffsetMax = "-5 20" },
                        Button = { Color = "1 1 1 0.75060739", Sprite = "assets/icons/clear.png", Command = $"xskin remove_ui {item} {skin} {Page} {(isAdminSkin ? "admin" : isVipSkin ? "vip" : "default")} {category}" },
                        Text = { Text = "" }
                    }, ".Skin");
				}
				
				container.Add(new CuiButton
                {
                    RectTransform = { AnchorMin = "0 0", AnchorMax = "0 0", OffsetMin = "5 5", OffsetMax = "20 20" },
                    Button = { Color = "1 1 1 0.75060739", Sprite = config.GUI.IconZoom, Command = $"skin_c zoomskin {itemid} {skin} false" },
                    Text = { Text = "" }
                }, ".Skin");
				
				if(isAdminSkin)
				    container.Add(new CuiPanel
                    {
                        RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2.5 -25", OffsetMax = "12.5 -15" },
                        Image = { Color = "0.9 0 0 1", Sprite = "assets/icons/circle_closed.png" },
                    }, ".Skin");
				else if(isVipSkin)
				    container.Add(new CuiPanel
                    {
                        RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2.5 -25", OffsetMax = "12.5 -15" },
                        Image = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/circle_closed.png" },
                    }, ".Skin");
				
				x++;
				
				if(x == 10)
				{
					x = 0;
					y++;
					
					if(y == yN)
						break;
				}
			}
			
			bool back = Page != 0;
			bool next = list_skins.Count > ((Page + 1) * count);
			
			container.Add(new CuiButton
            {    
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-245 -36.75" : "-190 -36.75", OffsetMax = config.GUI.Page ? "-155 -10" : "-100 -10" },
                Button = { Color = back ? config.GUI.ActiveBackColor : config.GUI.InactiveBackColor, Command = back ? $"page.xskinmenu skin {item} {Page - 1} {category} {PageC} {search}" : "" },
                Text = { Text = lang.GetMessage("BACK", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = back ? config.GUI.ActiveBackColorText : config.GUI.InactiveBackColorText }
            }, ".SkinGUI");
			
			if(config.GUI.Page)
				container.Add(new CuiLabel
				{
					RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-152.5 -36.75", OffsetMax = "-97.5 -10" },
					Text = { Text = $"{Page + 1}/{Math.Ceiling((double)list_skins.Count / count)}", Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 15, Color = "0.75 0.75 0.75 0.9" }
				}, ".SkinGUI");
			
			container.Add(new CuiButton
            {
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-95 -36.75", OffsetMax = "-5 -10" },
                Button = { Color = next ? config.GUI.ActiveNextReloadColor : config.GUI.InactiveNextReloadColor, Command = next ? $"page.xskinmenu skin {item} {Page + 1} {category} {PageC} {search}" : "" },
                Text = { Text = lang.GetMessage("NEXT", this, player.UserIDString), Align = TextAnchor.MiddleCenter, FontSize = 16, Font = "robotocondensed-regular.ttf", Color = next ? config.GUI.ActiveNextReloadColorText : config.GUI.InactiveNextReloadColorText }
            }, ".SkinGUI");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-255 -41.75" : "-200 -41.75", OffsetMax = config.GUI.Page ? "-250 0" : "-195 0" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".SkinGUI");
			
			container.Add(new CuiPanel
			{
				RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-400 -36.75" : "-345 -36.75", OffsetMax = config.GUI.Page ? "-260 -10" : "-205 -10" },
				Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
			}, ".SkinGUI", ".Input");
			
			container.Add(new CuiElement
            {
                Parent = ".Input",
                Components =
                {
                    new CuiInputFieldComponent { Text = string.IsNullOrEmpty(search) ? lang.GetMessage("SKIN_SEARCH", this, player.UserIDString) : search, Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 10, Command = comfort ? $"skin_c searchskin {item} {category} {PageC}" : $"skin_c searchskin {item} {category}", CharsLimit = 30, NeedsKeyboard = true, Color = "0.8 0.8 0.8 1" },
                    new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" }
                }
            });
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = config.GUI.Page ? "-410 -41.75" : "-355 -41.75", OffsetMax = config.GUI.Page ? "-405 0" : "-350 0" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".SkinGUI");
			
			CuiHelper.AddUi(player, container);
		}
		
		private void cmdSetSkinItem(BasePlayer player, string command, string[] args)
		{
			if(!permission.UserHasPermission(player.UserIDString, permSkinI))
			{
				SendReply(player, lang.GetMessage("NOPERM", this, player.UserIDString));
				return;
			}
			
			Item item = player.GetActiveItem();
			
			if(item != null)
			{
				string shortname = _redirectSkins.ContainsKey(item.info.shortname) ? _redirectSkins[item.info.shortname] : item.info.shortname;
				
				if(Blacklisted(item.skin))
				{
					EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
					return;
				}
				
				if(StoredDataSkins.ContainsKey(shortname) && _items.ContainsKey(shortname))
					SetItemGUI(player, shortname);
			}
		}
		
		private void GenerateItems()
		{
			foreach(var pair in Rust.Workshop.Approved.All)
			{
				if(pair.Value == null || pair.Value.Skinnable == null) continue;
				
				_allFPSkins.Add(pair.Value.WorkshopdId);
			}
			
			foreach(ItemDefinition item in ItemManager.GetItemDefinitions())
				foreach(var skin in ItemSkinDirectory.ForItem(item))
					_allFPSkins.Add(Convert.ToUInt64(skin.id));
			
			foreach(ulong skin in _freeSkins)
				_allFPSkins.Remove(skin);
			
			//FP_TOS
			if(config.Setting.FacepunchTOS)
			{
				foreach(var skins in StoredDataSkins)
					skins.Value.RemoveAll(skin => _allFPSkins.Contains(skin));
				
				foreach(var skins in config.Setting.AdminSkins)
					skins.Value.RemoveAll(skin => _allFPSkins.Contains(skin));
				
				foreach(var skins in config.Setting.VipSkins)
					skins.Value.RemoveAll(skin => _allFPSkins.Contains(skin));
				
				foreach(var category in config.Category)
				{
					var items = category.Value;
					
					foreach(var item in items.Keys.ToList())
						if(_allFPSkins.Contains(items[item]))
							items[item] = 0;
				}
				
				foreach(var kit in config.KitsSetting)
				{
					var items = kit.Value;
					
					foreach(var item in items.Keys.ToList())
						if(_allFPSkins.Contains(items[item]))
							items[item] = 0;
				}
				
				SaveConfig();
			}
			else
			{
				if(config.Setting.UpdateSkins)
					foreach(var pair in Rust.Workshop.Approved.All)
					{
						if(pair.Value == null || pair.Value.Skinnable == null) continue;
						
						ulong skinID = pair.Value.WorkshopdId; 
						string item = pair.Value.Skinnable.ItemName;
						
						if(item.Contains("lr300")) item = "rifle.lr300";
						
						_skinIDsINT[pair.Value.WorkshopdId] = (int)pair.Value.InventoryId;
						
						if(!StoredDataSkins.ContainsKey(item))
							StoredDataSkins.Add(item, new List<ulong>());
						
						if(!StoredDataSkins[item].Contains(skinID))
							StoredDataSkins[item].Add(skinID);
						
						StoredDataSkinsName[skinID] = pair.Value.Name;
					}
				
				if(config.Setting.UpdateSkinsFacepunch)
					foreach(ItemDefinition item in ItemManager.GetItemDefinitions())
					{
						foreach(var skin in ItemSkinDirectory.ForItem(item))
						{
							ulong skinID = Convert.ToUInt64(skin.id);
							
							if(!StoredDataSkins.ContainsKey(item.shortname))
								StoredDataSkins.Add(item.shortname, new List<ulong>());
							
							_skinIDsINT[skinID] = (int)skinID;
							
							if(!StoredDataSkins[item.shortname].Contains(skinID))
								StoredDataSkins[item.shortname].Add(skinID);
							
							StoredDataSkinsName[skinID] = skin.invItem.displayName.english;
						}
					}
			}
		   		 		  						  	   		   		 		  		 			   					  	 	 
			foreach(var item in config.Setting.AdminSkins)
			{
				_adminSkins.UnionWith(item.Value);
				
				if(StoredDataSkins.ContainsKey(item.Key))
						foreach(var skins in item.Value)
							if(StoredDataSkins[item.Key].Contains(skins))
								StoredDataSkins[item.Key].Remove(skins);
			}
				
			foreach(var item in config.Setting.VipSkins)
			{
				_vipSkins.UnionWith(item.Value);
				
				if(StoredDataSkins.ContainsKey(item.Key))
					foreach(var skins in item.Value)
						if(StoredDataSkins[item.Key].Contains(skins))
							StoredDataSkins[item.Key].Remove(skins);
			}
						
			_adminAndVipSkins.UnionWith(_adminSkins);
			_adminAndVipSkins.UnionWith(_vipSkins);
			
			StoredDataSkinsName[0] = "Default";
			
			SaveDataSkins();
			SaveDataSkinsName();
		}
		private const string permSkinI = "xskinmenu.item";
		
		[ConsoleCommand("page.xskinmenu")]
		private void ccmdPage(ConsoleSystem.Arg args)
		{
			BasePlayer player = args.Player();
			Effect x = new Effect("assets/bundled/prefabs/fx/notice/loot.drag.grab.fx.prefab", player, 0, new Vector3(), new Vector3());
			
			string item = args.GetString(1);
			int Page = args.GetInt(2);
			
			switch (args.GetString(0))
			{
				case "item":
				{
					if(StoredData[player.userID].Comfort)
						ItemGUI(player, item, Page, args.GetString(3));
					else
						ItemGUI(player, item, Page);
					
					break;
				}				
				case "skin":
				{
					string search = args.Args.Length >= 6 ? string.Join(" ", args.Args.Skip(5)).ToLower() : "";
					
					if(StoredData[player.userID].Comfort)
						SkinGUI(player, item, Page, args.GetString(3), args.GetInt(4), search);
					else
						SkinGUI(player, item, Page, "null", 0, search);
					
					break;
				}
				case "skinitem":
				{
					SetItemGUI(player, item, Page, args.GetBool(3), args.Args.Length >= 5 ? string.Join(" ", args.Args.Skip(4)).ToLower() : "");
					break;
				}
				case "defaultkits":
				{
					if(config.Setting.EnableDefaultKits) DefaultKitsGUI(player, Page);
					break;
				}				
				case "customkits":
				{
					if(config.Setting.EnableCustomKits) CustomKitsGUI(player, Page);
					break;
				}
			}
			
			if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
		}
		protected override void LoadDefaultConfig() => config = SkinConfig.GetNewConfiguration();
		
		private object OnItemSkinChange(int skinID, Item item, StorageContainer container, BasePlayer player)
		{
			if(Blacklisted(item.skin))
			{
				EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
				
				return false;
			}
			else
				return null;
		}
		private const string permEntity = "xskinmenu.entity";
		
				
				
		private Dictionary<ulong, List<ulong>> _workshopSkins = new Dictionary<ulong, List<ulong>>();
		
		private void CheckSkinIDImageLibrary(ulong skin, string url)
		{
			string imageName = skin.ToString();
			
			if(skin > 20000UL && !_allFPSkins.Contains(skin) && !ImageLibrary.Call<bool>("HasImage", imageName, _imageID))
				ImageLibrary.Call("AddImage", config.Setting.ImageAPIOption ? $"https://api.skyplugins.ru/api/skins/{skin}/icon?size=110&format=png" : url, imageName, _imageID);
		}
		
		private void ResetPlayerSkins(BasePlayer player)
		{
			if(StoredData.ContainsKey(player.userID))
			{
				if(!permission.UserHasPermission(player.UserIDString, permAdminS))
					foreach(var item in config.Setting.AdminSkins)
						if(StoredData[player.userID].Skins.ContainsKey(item.Key) && item.Value.Contains(StoredData[player.userID].Skins[item.Key]))
							StoredData[player.userID].Skins[item.Key] = 0;
					
				if(!permission.UserHasPermission(player.UserIDString, permVipS))
					foreach(var item in config.Setting.VipSkins)
						if(StoredData[player.userID].Skins.ContainsKey(item.Key) && item.Value.Contains(StoredData[player.userID].Skins[item.Key]))
							StoredData[player.userID].Skins[item.Key] = 0;
			}
		}
		
		private string BgMainLayer, BgKitsLayer;
		
		private void SetSkinEntity(BasePlayer player, BaseEntity entity, string shortname, ulong skin)
		{
			//if(skin == entity.skinID || skin == 0) return;
			if(skin == entity.skinID) return;
			if(ersK.ContainsKey(shortname) && ersK[shortname].ContainsKey(skin))
			{
				SendInfo(player, lang.GetMessage("ERRORSKIN", this, player.UserIDString));
				return;
			}
			
			entity.skinID = skin;
            entity.SendNetworkUpdate(BasePlayer.NetworkQueue.Update);
			Effect.server.Run("assets/prefabs/deployable/repair bench/effects/skinchange_spraypaint.prefab", entity.transform.localPosition);
		}
		
		private Data DATA()
		{
			Data data = new Data();
			
			data.ChangeSI = config.PSetting.ChangeSI;
			data.ChangeSCL = config.PSetting.ChangeSCL;
			data.ChangeSE = config.PSetting.ChangeSE;
			data.ChangeSG = config.PSetting.ChangeSG;
			data.ChangeSP = config.PSetting.ChangeSP;
			data.ChangeSGN = config.PSetting.ChangeSGN;
			data.ChangeSC = config.PSetting.ChangeSC;
			data.UseSprayC = config.PSetting.UseSprayC;
			data.UseSoundE = config.PSetting.UseSoundE;
			data.Comfort = config.PSetting.Comfort;
			
			return data;
		}
		private const string permGive = "xskinmenu.give";
		
		private void SetSkinTransport(BasePlayer player, BaseVehicle vehicle, string shortname)
		{
			ulong skin = StoredData[player.userID].Skins[shortname];
			
			if(skin == vehicle.skinID || skin == 0) return;
			
			if(errorskins.ContainsKey(skin))
				shortname = errorskins[skin];
			if(errorshortnames.ContainsKey(shortname))
				shortname = errorshortnames[shortname];
			
			BaseVehicle transport = GameManager.server.CreateEntity($"assets/content/vehicles/snowmobiles/{shortname}.prefab", vehicle.transform.position, vehicle.transform.rotation) as BaseVehicle;
			transport.health = vehicle.health;
			transport.skinID = skin;
			
			vehicle.Kill();
			transport.Spawn();
			Effect.server.Run("assets/prefabs/deployable/repair bench/effects/skinchange_spraypaint.prefab", transport.transform.localPosition);
		}
		
	    private void Unload()
		{
			foreach(BasePlayer player in BasePlayer.activePlayerList)
			{
				SaveData(player);
				CuiHelper.DestroyUi(player, BgMainLayer);
				CuiHelper.DestroyUi(player, ".SetItem");
				CuiHelper.DestroyUi(player, BgKitsLayer);
			}
			
			foreach(var coroutine in _coroutineListCollections)
				if(coroutine.Value != null)
					ServerMgr.Instance.StopCoroutine(coroutine.Value);
			
			if(_coroutineDownloadCommunitySkinsImage != null) 
				ServerMgr.Instance.StopCoroutine(_coroutineDownloadCommunitySkinsImage);
			
			if(StoredDataFriends != null && StoredDataFriends.Count != 0)
				Interface.Oxide.DataFileSystem.WriteObject("XDataSystem/XSkinMenu/Friends", StoredDataFriends);
		}
		
		private void CleanDictionary2(Dictionary<string, Dictionary<string, ulong>> dict, HashSet<ulong> invalidSkins)
		{
			foreach(KeyValuePair<string, Dictionary<string, ulong>> category in dict)
			{
				Dictionary<string, ulong> subDict = category.Value;
				List<string> keys = new List<string>(subDict.Keys);
				
				foreach(string itemKey in keys)
					if(invalidSkins.Contains(subDict[itemKey]))
						subDict[itemKey] = 0;
			}
		}
		private const string permPlayerAdd = "xskinmenu.playeradd";
		
		private void OnPlayerRespawned(BasePlayer player)
		{
			if(StoredData.TryGetValue(player.userID, out Data data) && !_removeATC.Contains(player.userID))
			{
				if(player.inventory.containerWear != null)
					foreach(Item item in player.inventory.containerWear.itemList.ToArray())
						if(data.Skins.ContainsKey(item.info.shortname)) 
							SetSkinCraftGive(player, item, true);
				
				if(player.inventory.containerMain != null)
					foreach(Item item in player.inventory.containerMain.itemList.ToArray())
						if(data.Skins.ContainsKey(item.info.shortname)) 
							SetSkinCraftGive(player, item, true);
				
				if(player.inventory.containerBelt != null)
					foreach(Item item in player.inventory.containerBelt.itemList.ToArray())
						if(data.Skins.ContainsKey(item.info.shortname)) 
							SetSkinCraftGive(player, item, true);
			}
			
			RemoveATC(player.userID);
		}
		
		private void CreateKitGUI(BasePlayer player)
		{
			CuiElementContainer container = new CuiElementContainer();
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 -45.5" },
                Image = { Color = config.GUI.BColor3 }
            }, ".CustomKitsGUI", ".CreateKitGUI", ".CreateKitGUI");
			
			container.Add(new CuiButton
            {    
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
                Button = { Color = "0 0 0 0", Close = ".CreateKitGUI" },
                Text = { Text = "" }
            }, ".CreateKitGUI");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-200 -34.25", OffsetMax = "200 34.25" },
                Image = { Color = config.GUI.BColor1, Material = "assets/icons/greyout.mat" }
            }, ".CreateKitGUI", ".CreateKit");
			
			container.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "5 5", OffsetMax = "-5 -5" },
                Image = { Color = config.GUI.BColor2 }
            }, ".CreateKit", ".CreateButtons");
			
			container.Add(new CuiPanel
			{
				RectTransform = { AnchorMin = "0.5 1", AnchorMax = "0.5 1", OffsetMin = "-105 -25", OffsetMax = "105 -5" },
				Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
			}, ".CreateButtons", ".Input");
			
			container.Add(new CuiElement
            {
                Parent = ".Input",
                Components =
                {
                    new CuiInputFieldComponent { Text = lang.GetMessage("KIT_NAME_CREATE", this, player.UserIDString), Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 12, Command = "skin_c createkit", CharsLimit = 18, NeedsKeyboard = true },
                    new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" }
                }
            });
			
			container.Add(new CuiLabel
			{
				RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMin = "5 5", OffsetMax = "-5 35" },
				Text = { Text = lang.GetMessage("CREATE_KIT_INFO", this, player.UserIDString), Align = TextAnchor.LowerCenter, FontSize = 10, Font = "robotocondensed-regular.ttf", Color = "0.5 0.5 0.5 0.75" }
			}, ".CreateButtons");
			
			CuiHelper.AddUi(player, container);
		}
		private const string permInv = "xskinmenu.inventory";
		private const string permSkinC = "xskinmenu.skinchange";

        public class SkinsResponse
        {
            public PublishedFileDetails[] publishedfiledetails;
        }
		
		private void BatchWEBRequest(List<ulong> allSkins, int startIndex, HashSet<ulong> invalidSkins, int attempt, int totalRequests)
		{
			int currentBatchCount = Math.Min(100, allSkins.Count - startIndex), currentBatchNum = (startIndex / 100) + 1;
			
			StringBuilder sb = new StringBuilder();
			sb.Append($"?key={config.Steam.APIKey}&itemcount={currentBatchCount}");
			
			for(int i = 0; i < currentBatchCount; i++)
				sb.Append($"&publishedfileids[{i}]={allSkins[startIndex + i]}");
			
			webrequest.Enqueue("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/", sb.ToString(), (code, response) =>
			{
				if(code == 200 && !string.IsNullOrEmpty(response))
				{
					PrintWarning((LanguageEnglish ? $"Request {currentBatchNum}/{totalRequests}" : $"Запрос {currentBatchNum}/{totalRequests}") + "  -  OK");
					
					ParseBatchWEBRequestResult(response, invalidSkins);
					
					int nextIndex = startIndex + currentBatchCount;
					
					if(nextIndex < allSkins.Count)
						timer.Once(0.5f, () => BatchWEBRequest(allSkins, nextIndex, invalidSkins, 1, totalRequests));
					else
						FinalizeAllSkinsRequest(invalidSkins);
				}
				else
				{
					if(attempt < 3)
						timer.Once(1f, () => BatchWEBRequest(allSkins, startIndex, invalidSkins, attempt + 1, totalRequests));
					else
					{
						PrintWarning((LanguageEnglish ? $"Request {currentBatchNum}/{totalRequests}" : $"Запрос {currentBatchNum}/{totalRequests}") + "  -  ERROR");
						
						int nextIndex = startIndex + currentBatchCount;
						
						if(nextIndex < allSkins.Count)
							timer.Once(0.5f, () => BatchWEBRequest(allSkins, nextIndex, invalidSkins, 1, totalRequests));
						else
							FinalizeAllSkinsRequest(invalidSkins);
					}
				}
			}, this, RequestMethod.POST);
		}
		
		private readonly Dictionary<string, string> _shortnamesEntity = new Dictionary<string, string>();
		 
		protected override void LoadConfig()
        {
            base.LoadConfig(); 
			 
			try
			{
				config = Config.ReadObject<SkinConfig>();
				
				if(config.Setting.AutoAddNewItems)
					foreach(var category in config.Category)
						if(_defaultCategoryAndItems.TryGetValue(category.Key, out List<string> items))
							foreach(string item in items)
								if(!category.Value.ContainsKey(item))
									category.Value.Add(item, 0UL);
			}
			catch
			{
				PrintWarning(LanguageEnglish ? "Configuration read error! Creating a default configuration!" : "Ошибка чтения конфигурации! Создание дефолтной конфигурации!");
				LoadDefaultConfig();
			}
			
			if(!config.Setting.ImageAPIOption)
				_imageID = 1150;
			
			if(config.Steam.IgnoredCommunitySkinsList == null)
				config.Steam.IgnoredCommunitySkinsList = new HashSet<ulong>();
			
			//FP_TOS
			if(config.Setting.FacepunchTOS)
			{
				config.Setting.UpdateSkins = false;
				config.Setting.UpdateSkinsFacepunch = false;
				
				config.Setting.ReissueActiveItem = false;
			}
			
			if(!config.Setting.ReissueActiveItem) config.Setting.ShiftUI = false;
			
			if(config.Setting.SkinCommandList == null) config.Setting.SkinCommandList = new List<string> { "skin" };
			if(config.Setting.SkinKitCommandList == null) config.Setting.SkinKitCommandList = new List<string> { "skinkit" };
			if(config.Setting.SkinEntityCommandList == null) config.Setting.SkinEntityCommandList = new List<string> { "skinentity" };
			if(config.Setting.SkinItemCommandList == null) config.Setting.SkinItemCommandList = new List<string> { "skinitem" };
			
			config.KitsCPSetting = config.KitsCPSetting.OrderByDescending(x => x.Value).ToDictionary(k => k.Key, v => v.Value);
			
			///DEL
			
			config.Setting.AdminSkins.Remove("wallpaper");
			config.Setting.VipSkins.Remove("wallpaper");
			
			foreach(var kit in config.KitsSetting)
				kit.Value.Remove("wallpaper");
			
			foreach(var category in config.Category)
				category.Value.Remove("wallpaper");

			///DEL
			
			_blacklist = new HashSet<ulong>(config.Setting.Blacklist);
			SaveConfig();
        }
		private Dictionary<ulong, string> StoredDataSkinsName = new Dictionary<ulong, string>();
		private Dictionary<string, int> _itemsId = new Dictionary<string, int>();
		private const string permCraft = "xskinmenu.craft";
		
		private void EntitySkin(BasePlayer player)
		{
			if(player.CanBuild())
			{
			    var entity = GetRHitEntity(player);
				
				if(entity == null) return;
				
				if(entity is BuildingBlock block)
				{
					if(_blocksWallpapersShortnames.TryGetValue(block.ShortPrefabName, out WallpaperSide wallpaperShortname))
					{
						string shortname = "";
						
						if(block.HasWallpaper(0) && block.CanSeeWallpaperSocket(player, 0))
							shortname = wallpaperShortname.Side;
						else if(block.HasWallpaper(1) && block.CanSeeWallpaperSocket(player, 1))
							shortname = wallpaperShortname.Side2;
						
						if(StoredDataSkins.ContainsKey(shortname) && _items.ContainsKey(shortname))
							SetItemGUI(player, shortname, 0, true);
					}
				}
				else if(entity is BaseVehicle vehicle)
				{
					if(StoredData[player.userID].Skins.ContainsKey(vehicle.ShortPrefabName))
						SetSkinTransport(player, vehicle, vehicle.ShortPrefabName);
				}
				else
					if(entity.OwnerID == player.userID || player.currentTeam != 0 && player.Team.members.Contains(entity.OwnerID) && StoredDataFriends.ContainsKey(entity.OwnerID) && StoredDataFriends[entity.OwnerID])
						if(_shortnamesEntity.ContainsKey(entity.ShortPrefabName))
						{
							if(Blacklisted(entity.skinID))
							{
								EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
								return;
							}
							
							string shortname = _shortnamesEntity[entity.ShortPrefabName];
							
							if(StoredDataSkins.ContainsKey(shortname) && _items.ContainsKey(shortname))
								SetItemGUI(player, shortname, 0, true);
						}
			}
		}
		
		private static readonly Dictionary<string, List<string>> _defaultCategoryAndItems = new Dictionary<string, List<string>>
		{
			["weapon"] = new List<string> { "gun.water", "pistol.revolver", "pistol.semiauto", "pistol.python", "pistol.eoka", "shotgun.waterpipe", "shotgun.double", "shotgun.pump", "bow.hunting", "crossbow", "grenade.f1", "smg.2", "smg.thompson", "smg.mp5", "rifle.ak", "rifle.lr300", "hmlmg", "lmg.m249", "rocket.launcher", "rifle.semiauto", "rifle.sks", "rifle.m39", "rifle.bolt", "rifle.l96", "longsword", "salvaged.sword", "mace", "knife.combat", "bone.club", "knife.bone", "spear.wooden" },
			["construction"] = new List<string> { "wall.frame.garagedoor", "door.double.hinged.toptier", "door.double.hinged.metal", "door.double.hinged.wood", "door.hinged.toptier", "door.hinged.metal", "door.hinged.wood", "barricade.concrete", "barricade.sandbags", "cupboard.tool", "wall.frame.lunar2025_a", "gates.external.high.stone", "wall.external.high.stone", "gates.external.high.wood", "wall.external.high", "lock.code", "wall.window.glass.reinforced", "wall.frame.shopfront.metal" },
			["item"] = new List<string> { "autoturret", "guntrap", "locker", "vending.machine", "fridge", "furnace", "electric.furnace", "furnace.large", "bbq", "table", "chair", "rockingchair", "box.wooden.large", "box.wooden", "rug.bear", "rug", "wallpaper.wall", "wallpaper.flooring", "wallpaper.ceiling", "bed", "sleepingbag", "computerstation", "water.purifier", "target.reactive", "sled", "discofloor", "paddlingpool", "innertube", "boogieboard", "beachtowel", "beachparasol", "beachchair", "skull.trophy", "skullspikes", "skylantern", "ceilinglight", "industrial.wall.light", "wantedposter", "spinner.wheel", "planter.large", "planter.triangle", "shelves" },
			["attire"] = new List<string> { "metal.facemask", "coffeecan.helmet", "riot.helmet", "bucket.helmet", "deer.skull.mask", "twitch.headset", "sunglasses", "mask.balaclava", "burlap.headwrap", "hat.miner", "hat.candle", "hat.beenie", "hat.boonie", "hat.cap", "hat.wolf", "mask.bandana", "metal.plate.torso", "roadsign.jacket", "roadsign.kilt", "roadsign.gloves", "burlap.gloves", "tactical.gloves", "attire.hide.poncho", "jacket.snow", "jacket", "tshirt.long", "hazmatsuit", "hoodie", "shirt.collared", "tshirt", "burlap.shirt", "attire.hide.vest", "shirt.tanktop", "attire.hide.helterneck", "pants", "burlap.trousers", "pants.shorts", "attire.hide.pants", "attire.hide.skirt", "shoes.boots", "burlap.shoes", "attire.hide.boots", "largebackpack", "smallbackpack", "wood.armor.helmet", "wood.armor.jacket", "wood.armor.pants", "woodarmor.gloves", "horse.armor.roadsign" },
			["tool"] = new List<string> { "fun.guitar", "jackhammer", "hammer.salvaged", "icepick.salvaged", "pickaxe", "stone.pickaxe", "rock", "chainsaw", "axe.salvaged", "hatchet", "stonehatchet", "explosive.satchel", "hammer", "torch" },
			["transport"] = new List<string> { "snowmobile" }
		};
		private Dictionary<string, ulong> _items = new Dictionary<string, ulong>();
		public readonly Dictionary<ulong, string> errorskins = new Dictionary<ulong, string> { [13068] = "snowmobiletomaha" };
		
		private Dictionary<ulong, string> _allCommunitySkinsURL = new Dictionary<ulong, string>();
		private const string permSprayC = "xskinmenu.spraycan";
		
		[ConsoleCommand("xskin")]
		private void ccmdAdmin(ConsoleSystem.Arg args)
		{
			if(args.Player() == null || permission.UserHasPermission(args.Player().UserIDString, permAdmin))
			{
				string item = args.GetString(1);
				BasePlayer player = args.Player();
				
				if(!StoredDataSkins.ContainsKey(item))
				{
					PrintWarning(LanguageEnglish ? $"No item <{item}> found in the list!" : $"Не найдено предмета <{item}> в списке!");
					return;
				}
				
				switch(args.GetString(0))
				{
					case "add": 
					{
						ulong skinID = args.GetULong(2);
						
						//FP_TOS
						if(config.Setting.FacepunchTOS && _allFPSkins.Contains(skinID))
						{
							PrintWarning($"FP_TOS_ERROR  -  {skinID}");
							return;
						}
						
						if(_adminAndVipSkins.Contains(skinID))
						{
							PrintWarning(LanguageEnglish ? $"Skin <{skinID}> cannot be added! Since he is ADMIN/VIP." : $"Скин <{skinID}> не может быть добавлен! Так как он АДМИНСКИЙ/ВИП.");
							return;
						}
						
						if(StoredDataSkins[item].Contains(skinID))
							PrintWarning(LanguageEnglish ? $"The skin <{skinID}> is already in the list of skins for the item <{item}>!" : $"Скин <{skinID}> уже есть в списке скинов предмета <{item}>!");
						else
						{
							StoredDataSkins[item].Add(skinID);
							PrintWarning(LanguageEnglish ? $"Skin <{skinID}> has been successfully added to the list of skins for item <{item}>!" : $"Скин <{skinID}> успешно добавлен в список скинов предмета <{item}>!");
							
							SaveDataSkins();
							
							webrequest.Enqueue("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/", $"?key={config.Steam.APIKey}&itemcount=1&publishedfileids[0]={skinID}", (code, response) => SkinNameRequest(code, response), this, RequestMethod.POST);
						}
						
						break;
					}					
					case "remove":
					{
						ulong skinID = args.GetULong(2);
						
						if(StoredDataSkins[item].Contains(skinID))
						{
							StoredDataSkins[item].Remove(skinID);
							PrintWarning(LanguageEnglish ? $"Skin <{skinID}> has been successfully removed from the list of skins for item <{item}>!" : $"Скин <{skinID}> успешно удален из списка скинов предмета <{item}>!");
							
							SaveDataSkins();
						}
						else
							PrintWarning(LanguageEnglish ? $"Skin <{skinID}> was not found in the list of skins for item <{item}>!" : $"Скин <{skinID}> не найден в списке скинов предмета <{item}>!");
						
						break;
					}
					case "remove_ui":
					{
						ulong skinID = args.GetULong(2);
						
						if(player != null)
						{
							switch(args.GetString(4))
							{
								case "default":
								{
									if(StoredDataSkins[item].Contains(skinID))
									{
										StoredDataSkins[item].Remove(skinID);
										PrintWarning(LanguageEnglish ? $"Skin <{skinID}> has been successfully removed from the list of skins for item <{item}>!" : $"Скин <{skinID}> успешно удален из списка скинов предмета <{item}>!");
										
										SaveDataSkins();
									}
									else
										PrintWarning(LanguageEnglish ? $"Skin <{skinID}> was not found in the list of skins for item <{item}>!" : $"Скин <{skinID}> не найден в списке скинов предмета <{item}>!");
									
									break;
								}
								case "admin":
								{
									if(config.Setting.AdminSkins.ContainsKey(item) && config.Setting.AdminSkins[item].Contains(skinID))
									{
										config.Setting.AdminSkins[item].Remove(skinID);
										PrintWarning(LanguageEnglish ? $"Skin <{skinID}> has been successfully removed from the list of Admin skins for item <{item}>!" : $"Скин <{skinID}> успешно удален из списка Админ скинов предмета <{item}>!");
										
										SaveConfig();
									}
									else
										PrintWarning(LanguageEnglish ? $"Skin <{skinID}> was not found in the list of Admin skins for item <{item}>!" : $"Скин <{skinID}> не найден в списке Админ скинов предмета <{item}>!");
									
									break;
								}
								case "vip":
								{
									if(config.Setting.VipSkins.ContainsKey(item) && config.Setting.VipSkins[item].Contains(skinID))
									{
										config.Setting.VipSkins[item].Remove(skinID);
										PrintWarning(LanguageEnglish ? $"Skin <{skinID}> has been successfully removed from the list of Vip skins for item <{item}>!" : $"Скин <{skinID}> успешно удален из списка Вип скинов предмета <{item}>!");
										
										SaveConfig();
									}
									else
										PrintWarning(LanguageEnglish ? $"Skin <{skinID}> was not found in the list of Vip skins for item <{item}>!" : $"Скин <{skinID}> не найден в списке Вип скинов предмета <{item}>!");
									
									break;
								}
							}
							
							if(config.GUI.DelSkinUP) SkinGUI(player, item, args.GetInt(3), args.GetString(5));
							EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/weapons/survey_charge/survey_charge_stick.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
						}
						
						break;
					}
					case "refresh_ui":
					{
						ulong skinID = args.GetULong(2);
						
						if(player != null)
						{
							switch(args.GetString(4))
							{
								case "admin_to_default":
								{
									if(config.Setting.AdminSkins.ContainsKey(item) && config.Setting.AdminSkins[item].Contains(skinID))
									{
										config.Setting.AdminSkins[item].Remove(skinID);
										if(!StoredDataSkins[item].Contains(skinID)) StoredDataSkins[item].Add(skinID);
											
										_adminSkins.Remove(skinID);
										_adminAndVipSkins.Remove(skinID);
									}
									
									break;
								}
								case "vip_to_default":
								{
									if(config.Setting.VipSkins.ContainsKey(item) && config.Setting.VipSkins[item].Contains(skinID))
									{
										config.Setting.VipSkins[item].Remove(skinID);
										if(!StoredDataSkins[item].Contains(skinID)) StoredDataSkins[item].Add(skinID);
											
										_vipSkins.Remove(skinID);
										_adminAndVipSkins.Remove(skinID);
									}
									
									break;
								}
								case "default_to_admin":
								{
									if(StoredDataSkins[item].Contains(skinID))
										if(config.Setting.AdminSkins.ContainsKey(item))
										{
											StoredDataSkins[item].Remove(skinID);
											if(!config.Setting.AdminSkins[item].Contains(skinID)) config.Setting.AdminSkins[item].Add(skinID);
										}
										else
										{
											config.Setting.AdminSkins.Add(item, new List<ulong>());
											
											StoredDataSkins[item].Remove(skinID);
											config.Setting.AdminSkins[item].Add(skinID);
										}
										
									if(!_adminSkins.Contains(skinID)) _adminSkins.Add(skinID);
									if(!_adminAndVipSkins.Contains(skinID)) _adminAndVipSkins.Add(skinID);
									
									break;
								}
								case "default_to_vip":
								{
									if(StoredDataSkins[item].Contains(skinID))
										if(config.Setting.VipSkins.ContainsKey(item))
										{
											StoredDataSkins[item].Remove(skinID);
											if(!config.Setting.VipSkins[item].Contains(skinID)) config.Setting.VipSkins[item].Add(skinID);
										}
										else
										{
											config.Setting.VipSkins.Add(item, new List<ulong>());
											
											StoredDataSkins[item].Remove(skinID);
											config.Setting.VipSkins[item].Add(skinID);
										}
										
									if(!_vipSkins.Contains(skinID)) _vipSkins.Add(skinID);
									if(!_adminAndVipSkins.Contains(skinID)) _adminAndVipSkins.Add(skinID);
									
									break;
								}
							}
							
							if(config.GUI.DelSkinUP) SkinGUI(player, item, args.GetInt(3), args.GetString(5));
							EffectNetwork.Send(new Effect("assets/prefabs/tools/c4/effects/c4_stick.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
							
							SaveConfig();
							SaveDataSkins();
						}
						
						break;
					}
					case "list": 
					{
						if(StoredDataSkins[item].Count == 0)
						{
							PrintWarning(LanguageEnglish ? $"The list of skins for item <{item}> is empty!" : $"Список скинов предмета <{item}> пуст!");
							return;
						}
						
						string skinslist = LanguageEnglish ? $"List of item skins <{item}>:\n" : $"Список скинов предмета <{item}>:\n";
						
						foreach(ulong skinID in StoredDataSkins[item])
						    skinslist += $"\n{skinID}";
						
						PrintWarning(skinslist);
						
						break;
					}					
					case "clearlist":
					{
						if(StoredDataSkins[item].Count == 0)
							PrintWarning(LanguageEnglish ? $"The list of skins for the item <{item}> is already empty!" : $"Список скинов предмета <{item}> уже пуст!");
						else
						{
							StoredDataSkins[item].Clear();
							PrintWarning(LanguageEnglish ? $"The list of skins for item <{item}> has been cleared successfully!" : $"Список скинов предмета <{item}> успешно очищен!");
							
							SaveDataSkins();
						} 
						
						break;  
					}					  
				}
			}
		}
		private List<ulong> API_GetBlacklist() => config.Setting.Blacklist;
		private const string permAdmin = "xskinmenu.admin";
		
		private void AddRemoveSkins(string[] skinIDs, bool addOrRemoveSkin)
		{
			List<ulong> IDs = new List<ulong>();
			
			for(int i = 1; i < skinIDs.Length; i++)
				if(ulong.TryParse(skinIDs[i], out ulong ID))
					IDs.Add(ID);
				else
					PrintWarning(LanguageEnglish ? $"SkinID {skinIDs[i]} is invalid!" : $"СкинИД {skinIDs[i]} недействителен!");
				
			string details = $"?key={config.Steam.APIKey}&itemcount={IDs.Count}";
			
			for(int i = 0; i < IDs.Count; i++)
				details += $"&publishedfileids[{i}]={IDs[i]}";
			
			webrequest.Enqueue("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/", details, (code, response) => AddRemoveSkinsRequest(code, response, addOrRemoveSkin, IDs.Count), this, RequestMethod.POST);
		}
		
			}
}
