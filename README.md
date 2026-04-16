# 🔐 Password Manager (Java Swing)

A simple and secure **Password Manager desktop application** built using **Java (Swing + OOP concepts)**.
This application allows users to store, manage, and retrieve passwords in a user-friendly interface.

---

## 🚀 Features

* 🔑 **Master Password Protection**

  * Login system with a master password (`admin123` by default)

* ➕ **Add Credentials**

  * Store website, username, and password

* 🔍 **Search Functionality**

  * Quickly find entries by site or username

* 👁 **Show/Hide Password**

  * Toggle password visibility

* 📋 **Copy Password**

  * Copy password directly to clipboard

* 🗑 **Delete Entries**

  * Remove selected credentials

* 🔤 **Sort Entries**

  * Sort all entries alphabetically

* 📂 **Import from CSV**

  * Import passwords from Google Password Manager

* 💾 **Auto Save**

  * Data is automatically saved in `passwords.csv`

---

## 🛠️ Technologies Used

* Java
* Swing (GUI)
* OOP Concepts
* File Handling (CSV)
* Base64 Encoding

---

## 📁 Project Structure

* `PasswordEntry` → Stores individual credential details
* `VaultManager` → Handles logic (add, delete, search, file operations)
* `PasswordVaultApp` → Main GUI application

---

## 🔒 Security Note

* Passwords are stored using **Base64 encoding**
* ⚠️ This is **not fully secure encryption** (for learning/demo purpose only)

---

## ▶️ How to Run

1. Compile the program:

```bash
javac PasswordVaultApp.java
```

2. Run the program:

```bash
java PasswordVaultApp
```

3. Login using:

```
Username: (not required)
Password: admin123
```

---

## 📸 UI Preview

* Dark-themed modern UI
* Table-based password display
* Interactive buttons and controls

---

## 📌 Future Improvements

* Add strong encryption (AES)
* Cloud sync support
* Password strength checker
* Export to different formats

---

## 👨‍💻 Author

**Rudra Singh**

---

## ⭐ Note

This project is created for **learning Java OOP and GUI development**.

---
