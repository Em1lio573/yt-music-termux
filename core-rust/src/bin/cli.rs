use std::io::{self, Write};
use ytmusic_core::models::{DownloadCallback, DownloadProgress};
use ytmusic_core::{ytm_get_version, ytm_search_music, ytm_start_download, ytm_scan_library};

struct ConsoleCallback;

impl DownloadCallback for ConsoleCallback {
    fn on_progress(&self, p: DownloadProgress) {
        print!(
            "\r[{:11}] {:>5.1}% | {:>5.2} MB/s | ETA: {:>3}s",
            p.state, p.progress_percent, p.speed_mb_s, p.eta_seconds
        );
        let _ = io::stdout().flush();
        if p.state == "completed" {
            println!("\n¡Descarga completada con éxito!");
            if let Some(path) = p.saved_path {
                println!("Guardado en: {}", path);
            }
        } else if p.state == "error" {
            println!("\nError: {}", p.error_message.unwrap_or_default());
        }
    }
}

fn main() {
    println!("\x1b[96m====================================================\x1b[0m");
    println!("\x1b[96m     {}       \x1b[0m", ytm_get_version());
    println!("\x1b[96m     Descargador de Alta Fidelidad en Rust Nativo   \x1b[0m");
    println!("\x1b[96m====================================================\x1b[0m\n");

    let args: Vec<String> = std::env::args().collect();
    if args.len() > 1 {
        let query = args[1..].join(" ");
        realizar_busqueda_y_descarga(&query);
    } else {
        menu_interactivo();
    }
}

fn menu_interactivo() {
    loop {
        println!("\n\x1b[93m--- MENÚ PRINCIPAL ---\x1b[0m");
        println!("1. Buscar y descargar canción en YouTube Music");
        println!("2. Escanear biblioteca local");
        println!("3. Salir");
        print!("\nSelecciona una opción [1-3]: ");
        let _ = io::stdout().flush();

        let mut input = String::new();
        if io::stdin().read_line(&mut input).is_err() {
            break;
        }

        match input.trim() {
            "1" => {
                print!("Ingresa el nombre de la canción o artista: ");
                let _ = io::stdout().flush();
                let mut query = String::new();
                if io::stdin().read_line(&mut query).is_ok() && !query.trim().is_empty() {
                    realizar_busqueda_y_descarga(query.trim());
                }
            }
            "2" => {
                print!("Carpeta a escanear (por defecto './Biblioteca'): ");
                let _ = io::stdout().flush();
                let mut folder = String::new();
                let _ = io::stdin().read_line(&mut folder);
                let path = if folder.trim().is_empty() { "./Biblioteca" } else { folder.trim() };
                println!("Escaneando carpeta '{}'...", path);
                let tracks = ytm_scan_library(path.to_string());
                println!("Encontradas {} canciones:", tracks.len());
                for (i, t) in tracks.iter().take(20).enumerate() {
                    println!("{}. {} - {} [{}s, {}]", i + 1, t.artist, t.title, t.duration_sec, t.format);
                }
            }
            "3" => {
                println!("¡Hasta luego!");
                break;
            }
            _ => println!("Opción no válida."),
        }
    }
}

fn realizar_busqueda_y_descarga(query: &str) {
    println!("\n\x1b[94mBuscando '{}' en YouTube Music...\x1b[0m", query);
    let results = ytm_search_music(query.to_string());

    if results.is_empty() {
        println!("\x1b[91mNo se encontraron canciones para '{}'.\x1b[0m", query);
        return;
    }

    println!("\n\x1b[92mResultados encontrados:\x1b[0m");
    for (i, track) in results.iter().take(5).enumerate() {
        println!(
            "  [{}] {} - {} (Álbum: {}, {}s) [{}]",
            i + 1,
            track.artist,
            track.title,
            track.album,
            track.duration_sec,
            track.source
        );
    }

    print!("\nElige una pista para descargar [1-{}], o 'c' para cancelar: ", results.len().min(5));
    let _ = io::stdout().flush();

    let mut choice = String::new();
    if io::stdin().read_line(&mut choice).is_err() {
        return;
    }

    if let Ok(idx) = choice.trim().parse::<usize>() {
        if idx >= 1 && idx <= results.len().min(5) {
            let selected = &results[idx - 1];
            println!("\nDescargando: {} - {}...", selected.artist, selected.title);
            let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
            let default_dest = format!("{}/storage/music", home);

            let cb = Box::new(ConsoleCallback);
            match ytm_start_download(selected.clone(), "native".to_string(), default_dest, cb) {
                Ok(path) => println!("Proceso finalizado en: {}", path),
                Err(e) => println!("\nError al descargar: {}", e),
            }
        }
    }
}
