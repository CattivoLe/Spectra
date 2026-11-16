# Probe Lab — иконка

- `icon-1024.png` — финальный мастер 1024×1024, **без альфа-канала** (как требует App Store).
- `icon-master.svg` — редактируемый исходник (цвета, количество «пикселей», мотив меняются здесь, потом ре-рендер).
- `AppIcon.appiconset/` — готовый набор для Xcode (iOS, single-size universal — iOS сам генерирует остальные размеры).

## Использование
Перетащи `AppIcon.appiconset` в `Assets.xcassets` нового таргета Probe Lab (заменив пустой AppIcon), либо в билд-настройках укажи `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`.

## Ре-рендер из SVG
```sh
qlmanage -t -s 1024 -o . icon-master.svg
# затем убрать альфу (см. ниже) → icon-1024.png
python3 -I -c "from PIL import Image; im=Image.open('icon-master.svg.png').convert('RGBA'); bg=Image.new('RGB',im.size,(1,1,3)); bg.paste(im,mask=im.split()[3]); bg.save('icon-1024.png')"
```

## На будущее (опционально)
Для iOS 18 можно добавить dark и tinted варианты (отдельные записи в `Contents.json` с `appearances`). Сейчас один универсальный значок — он валиден и используется во всех режимах.
