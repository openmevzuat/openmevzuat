# OpenMevzuat

OpenMevzuat, Türkiye mevzuatını resmi kamu kaynaklarından çekip madde bazlı, okunabilir ve Git ile versiyonlanabilir dosyalar halinde saklayan açık kaynak bir projedir.

Bu proje resmi kaynak değildir ve hukuki tavsiye sunmaz. Nihai kaynaklar mevzuat.gov.tr, Resmî Gazete ve Türkiye Büyük Millet Meclisi gibi resmi kurumlardır.

## Kapsam

OpenMevzuat Türkiye mevzuatına odaklanır.

Desteklenen belge türleri:

- `constitution` — Anayasa
- `law` — Kanun
- `decree` — Kararname

Desteklenen kararname alt türleri:

- `khk` — Kanun Hükmünde Kararname
- `cbk` — Cumhurbaşkanlığı Kararnamesi

Yönetmelikler henüz kapsamda değildir.

## Veri Yapısı

Canonical dosyalar türlerine göre saklanır:

```text
data/canonical/constitution/
data/canonical/laws/
data/canonical/decrees/

data/metadata/constitution/
data/metadata/laws/
data/metadata/decrees/
```

Kararname kimlikleri ve slug formatları:

```text
decree/khk-{number}
decree/cbk-{number}

khk-{number}-{slugified-title}
cbk-{number}-{slugified-title}
```

## Uyarı

OpenMevzuat resmi kaynak değildir. Herhangi bir mevzuat metnine güvenmeden önce resmi kaynaklardan doğrulama yapılmalıdır.
