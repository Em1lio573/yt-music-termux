use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::RwLock;
use reqwest::cookie::Jar;
use url::Url;

pub struct CookieManager {
    pub jar: Arc<Jar>,
    pub has_session: AtomicBool,
    pub sapisid: RwLock<Option<String>>,
}

impl CookieManager {
    pub fn new() -> Self {
        Self {
            jar: Arc::new(Jar::default()),
            has_session: AtomicBool::new(false),
            sapisid: RwLock::new(None),
        }
    }

    /// Parsea una cabecera HTTP estándar de cookies ("name1=val1; name2=val2")
    /// generada por el WebView de Android al iniciar sesión en YouTube Music.
    pub fn parse_cookie_header(&self, header: &str) -> usize {
        let mut count = 0;
        let music_url = match Url::parse("https://music.youtube.com") {
            Ok(u) => u,
            Err(_) => return 0,
        };
        let yt_url = match Url::parse("https://www.youtube.com") {
            Ok(u) => u,
            Err(_) => return 0,
        };

        for item in header.split(';') {
            let item = item.trim();
            if item.is_empty() {
                continue;
            }

            if let Some((name_raw, val_raw)) = item.split_once('=') {
                let name = name_raw.trim();
                let value = val_raw.trim();

                // Detectar credenciales de sesión activa de Google / YouTube
                if name == "SAPISID" || name == "__Secure-3PAPISID" {
                    if let Ok(mut lock) = self.sapisid.write() {
                        *lock = Some(value.to_string());
                    }
                    self.has_session.store(true, Ordering::SeqCst);
                } else if name == "LOGIN_INFO" || name == "__Secure-3PSID" || name == "SID" {
                    self.has_session.store(true, Ordering::SeqCst);
                }

                let cookie_str = format!("{}={}; Domain=.youtube.com; Path=/", name, value);
                self.jar.add_cookie_str(&cookie_str, &music_url);
                self.jar.add_cookie_str(&cookie_str, &yt_url);
                count += 1;
            }
        }
        count
    }

    /// Parsea un archivo de cookies en formato Netscape de 7 columnas separadas por tabulador.
    pub fn parse_netscape_cookies(&self, content: &str) -> usize {
        let mut count = 0;
        for line in content.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }

            let parts: Vec<&str> = line.split('\t').collect();
            if parts.len() >= 7 {
                let domain = parts[0];
                let _flag = parts[1];
                let path = parts[2];
                let secure = parts[3].to_lowercase() == "true";
                let _expiration = parts[4];
                let name = parts[5];
                let value = parts[6];

                if name == "SAPISID" || name == "LOGIN_INFO" || name == "__Secure-3PSID" {
                    self.has_session.store(true, Ordering::SeqCst);
                }
                if name == "SAPISID" {
                    if let Ok(mut lock) = self.sapisid.write() {
                        *lock = Some(value.to_string());
                    }
                }

                let scheme = if secure { "https" } else { "http" };
                let domain_clean = domain.trim_start_matches('.');
                let url_str = format!("{}://{}{}", scheme, domain_clean, path);

                if let Ok(url) = Url::parse(&url_str) {
                    let cookie_str = format!("{}={}; Domain={}; Path={}", name, value, domain, path);
                    self.jar.add_cookie_str(&cookie_str, &url);
                    count += 1;
                }
            }
        }
        count
    }

    pub fn has_session(&self) -> bool {
        self.has_session.load(Ordering::SeqCst)
    }
}
