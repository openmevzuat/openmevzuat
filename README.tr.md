# OpenMevzuat

OpenMevzuat, Türkiye mevzuatını resmi kamu kaynaklarından çekip madde bazlı, okunabilir ve Git ile versiyonlanabilir dosyalar halinde saklayan açık kaynak bir projedir.

Bu proje resmi kaynak değildir ve hukuki tavsiye sunmaz. Nihai kaynaklar mevzuat.gov.tr, Resmî Gazete ve Türkiye Büyük Millet Meclisi gibi resmi kurumlardır.

İngilizce sürüm: [README.md](README.md)

## Durum

Deneysel. Resmi kaynak değildir.

## Kapsam

OpenMevzuat Türkiye mevzuatına odaklanır.

Çok ülkeli bir hukuk veri platformu olmayı hedeflemez.

Desteklenen belge türleri:

- `constitution` — Anayasa
- `law` — Kanun
- `decree` — Kararname

Desteklenen kararname alt türleri:

- `khk` — Kanun Hükmünde Kararname
- `cbk` — Cumhurbaşkanlığı Kararnamesi

Yönetmelikler henüz kapsamda değildir.

## Hedefler

- Mevzuat değişikliklerini zaman içinde takip etmek
- Canonical metni insan tarafından okunabilir tutmak
- Git diff'lerini anlamlı kılmak
- Her belgeyi resmi kaynağına bağlamak

## Hedef Olmayanlar

- Hukuki tavsiye
- Siyasi puanlama
- Kişi profilleme
- Otomatik hukuki yorum
- Web arayüzü, veritabanı, API, kimlik doğrulama, faturalama veya kuyruk altyapısı

## Veri Yapısı

Canonical dosyalar tek doğruluk kaynağıdır:

```text
data/canonical/**/README.md
data/canonical/**/articles/*.md
data/metadata/**/*.edn
```

Türetilmiş dosyalar silinip yeniden üretilebilir:

```text
derived/full-text/**/*.md
derived/search/*.jsonl
derived/diffs/**/*.edn
```

Canonical dosyalar türlerine göre saklanır:

```text
data/canonical/constitution/
data/canonical/laws/
data/canonical/decrees/

data/metadata/constitution/
data/metadata/laws/
data/metadata/decrees/
```

## Belge Kimlikleri ve Slug'lar

Belge kimlikleri:

```text
constitution/1982
law/{number}
decree/khk-{number}
decree/cbk-{number}
```

Kararname slug formatları:

```text
khk-{number}-{slugified-title}
cbk-{number}-{slugified-title}
```

Örnek:

```clojure
{:document/id "decree/cbk-1"
 :document/type :decree
 :decree/subtype :cbk
 :document/number "1"
 :document/title "Cumhurbaşkanlığı Teşkilatı Hakkında Cumhurbaşkanlığı Kararnamesi"
 :document/slug "cbk-1-cumhurbaskanligi-teskilati-hakkinda-cumhurbaskanligi-kararnamesi"
 :document/path "data/canonical/decrees/cbk-1-cumhurbaskanligi-teskilati-hakkinda-cumhurbaskanligi-kararnamesi"}
```

## Komutlar

```bash
clojure -M:openmevzuat sync-catalog
clojure -M:openmevzuat update
clojure -M:openmevzuat update-configured
clojure -M:openmevzuat update-laws 193 2918
clojure -M:openmevzuat update-all-laws
clojure -M:openmevzuat update-all-laws --resume-from 702
clojure -M:openmevzuat build
clojure -M:openmevzuat clean-derived
```

`update` normal günlük akıştır:

1. `data/catalog/laws.edn` içindeki resmi yürürlükteki Kanunlar kataloğunu yeniler;
2. yenilenen kataloğu yerel metadata ile karşılaştırıp henüz kaydedilmemiş kanunları toplar;
3. Resmî Gazete'yi son Yasama/Kanun kayıtları için sorgular;
4. daha önce işlenmemiş değişiklik kanunlarını belirler;
5. yalnızca bu yeni değişiklik kanunlarının `MADDE` girişlerini ayrıştırıp etkilenen mevzuat numaralarını çıkarır;
6. bu numaraları yerel katalog ve yapılandırılmış kararnameler üzerinden çözer;
7. etkilenen konsolide PDF'leri, 2. adımda bulunan kanunlarla birlikte çeker ve işler;
8. başarıyla işlenen değişiklik kanunlarını `data/state/resmigazete-amendments.edn` dosyasına kaydeder.

2. adım şu nedenle vardır: Resmî Gazete akışı bir kanunu ancak yakın tarihli bir değişiklik kanunu onu değiştirdiğini belirttiğinde bulur. Hiçbir kanunu değiştirmeyen yeni bir kanun bu akış için görünmezdir ve katalog karşılaştırması olmadan hiç çekilmez. Karşılaştırma, katalog ve yapılandırılmış belgelerin birleştirilmiş kümesi üzerinden yapılır: yapılandırılmış bir kanun katalog kaydını geçersiz kılar ve ikisi aynı kanun için farklı başlıklar taşıyabilir; aksi halde zaten kayıtlı kanunlar eksik görünür.

`data/state/resmigazete-amendments.edn` dosyasını yalnızca değişiklikten etkilenen kanunlar ilerletebilir. 2. adımda eklenen bir kanun kendi dosyalarını yazar; bu yazımların sayılması, kendi kanunları hiç değişmemiş değişiklik kanunlarının işlenmiş sayılmasına yol açardı.

Çözülemeyen etkilenen mevzuat kaldığı sürece 7. adım ilerlemez; bu durumda aynı değişiklik kanunları her çalışmada yeniden işlenir.

`sync-catalog` yalnızca kataloğu günceller, PDF çekmez veya işlemez.

`update-configured` `resources/documents.edn` içindeki yapılandırılmış kaynakları çeker, metni normalize eder, maddeleri ayrıştırır, canonical dosyaları ve EDN meta verisini yazar. `build` bu akışın diğer adıdır.

`update-laws` yalnızca istenen kanun numaralarını veya belge kimliklerini günceller. Katalogdaki tertip çakışmaları `update-laws t5-3201` şeklinde adreslenebilir.

`update-all-laws` bilinçli tam katalog yeniden inşasıdır. Yarıda kalırsa ilerleme indeksinden devam edilebilir:

```bash
clojure -M:openmevzuat update-all-laws --resume-from 702
clojure -M:openmevzuat update-all-laws --resume-after 704
```

Ayrıştırıcı geliştirmesi için geçici fixture modu:

```bash
OPENMEVZUAT_FIXTURE_MODE=true clojure -M:openmevzuat build
```

Fixture modu resmi kaynaklı canonical verinin yerine geçmez.

## Ortam Değişkenleri

Tüm değişkenler isteğe bağlıdır. Boolean değişkenler `true`, `1` veya `yes` değerlerini kabul eder.

| Değişken | Varsayılan | Amaç |
| --- | --- | --- |
| `OPENMEVZUAT_UPDATE_WINDOW_DAYS` | `30` | `update` için Resmî Gazete geriye bakış penceresi. |
| `OPENMEVZUAT_SNAPSHOT_DATE` | bugün (UTC) | Manifest ve meta veride kaydedilen anlık görüntü tarihini değiştirir. |
| `OPENMEVZUAT_PR_BODY_PATH` | tanımsız | Ayarlandığında `update` otomasyon PR açıklamasını bu dosyaya yazar. |
| `OPENMEVZUAT_FIXTURE_MODE` | tanımsız | Resmi kaynaklar yerine yerel fixture dosyalarını okur. Yalnızca geliştirme içindir. |
| `OPENMEVZUAT_SKIP_UNREACHABLE_SOURCES` | tanımsız | Resmi kaynağa erişilemediğinde hata vermek yerine temiz çıkış yapar. |
| `OPENMEVZUAT_FETCH_ATTEMPTS` | `6` | Kaynak isteği başına deneme sayısı. |
| `OPENMEVZUAT_FETCH_DELAY_MS` | `2500` | Ardışık kaynak istekleri arasındaki en az bekleme. |
| `OPENMEVZUAT_FETCH_BACKOFF_MS` | `5000` | Başarısız denemeden sonraki temel bekleme. |
| `OPENMEVZUAT_FETCH_JITTER_MS` | `250` | Her beklemeye eklenen rastgele sapma. |
| `OPENMEVZUAT_FETCH_MAX_BACKOFF_MS` | `120000` | Bekleme süresinin üst sınırı. |
| `OPENMEVZUAT_FETCH_CONNECT_TIMEOUT_MS` | `60000` | Kaynak istekleri için bağlantı zaman aşımı. |
| `OPENMEVZUAT_FETCH_TIMEOUT_MS` | `300000` | Kaynak istekleri için toplam istek zaman aşımı. |
| `OPENMEVZUAT_PREFLIGHT_ENABLED` | `true` | Asıl istekten önce kaynak erişilebilirliğini yoklar. |
| `OPENMEVZUAT_PREFLIGHT_ATTEMPTS` | `2` | Ön yoklama deneme sayısı. |
| `OPENMEVZUAT_CIRCUIT_BREAKER_FAILURES` | `3` | Kaynak devresi açılmadan önceki ardışık hata sayısı. |
| `OPENMEVZUAT_CATALOG_PAGE_SIZE` | `100` | mevzuat.gov.tr katalog sorgusu sayfa boyutu. |
| `OPENMEVZUAT_RESMIGAZETE_PAGE_SIZE` | `100` | Resmî Gazete sorgusu sayfa boyutu. |

Günlük iş akışı çekme değişkenlerinin çoğu için ayarlanmış değerler kullanır; yukarıdaki varsayılanlar hiçbir değer verilmediğinde CLI'nin kullandığı değerlerdir.

## Lisans ve Veri

OpenMevzuat **AGPL-3.0-only** lisansı ile lisanslanmıştır. Lisansın tam metni [COPYING](COPYING) dosyasındadır; proje bildirimi [LICENSE](LICENSE) dosyasındadır.

AGPL-3.0 madde 13 gereği, OpenMevzuat'ın değiştirilmiş bir sürümünü ağ üzerinden çalıştıran herkes, o sürümün tam kaynak kodunu kullanıcılarına sunmak zorundadır.

Proje kodu ve projeye özgü yapılandırılmış veriler, aksi belirtilmedikçe AGPL-3.0-only altındadır. Resmi hukuk metinleri resmi kamu kaynaklarına aittir; bkz. [DATA_LICENSE](DATA_LICENSE).

Fork ve türev çalışmalar telif bildirimini korumak, yapılan değişiklikleri belirtmek ve çalışmanın tamamını AGPL-3.0-only altında lisanslamak zorundadır. Bu şartlar kullanımınıza uymuyorsa [COMMERCIAL.md](COMMERCIAL.md) dosyasına bakınız.

## Uyarı

OpenMevzuat resmi kaynak değildir. Herhangi bir mevzuat metnine güvenmeden önce resmi kaynaklardan doğrulama yapılmalıdır.
