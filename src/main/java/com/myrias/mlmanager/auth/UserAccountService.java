package com.myrias.mlmanager.auth;

import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Hashing de senha + CRUD básico. Espelho de backend/app/services/user_service.py.
 */
@Service
public class UserAccountService {

    private final UserRepository repo;
    private final PasswordEncoder encoder;

    public UserAccountService(UserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    public long count() {
        return repo.count();
    }

    public List<AppUser> listActivePublic() {
        return repo.findByActiveTrueOrderByUsername();
    }

    /** Todos os usuários (CRUD de admin), ordenados por username. */
    public List<AppUser> listAll() {
        return repo.findAllByOrderByUsername();
    }

    public AppUser getById(Long id) {
        return repo.findById(id).orElse(null);
    }

    public AppUser getByUsername(String username) {
        return repo.findByUsername(username == null ? "" : username.strip()).orElse(null);
    }

    /** Hash bcrypt de uma senha — usado ao trocar a senha num update. */
    public String hashPassword(String plain) {
        return encoder.encode(plain);
    }

    /** Persiste alterações de um usuário gerenciado/destacado (merge). */
    public AppUser save(AppUser user) {
        return repo.save(user);
    }

    public void delete(AppUser user) {
        repo.delete(user);
    }

    public AppUser create(String username, String password, String displayName,
                          boolean admin, List<String> permissions) {
        AppUser user = new AppUser(
                username.strip(),
                displayName == null || displayName.isBlank() ? null : displayName.strip(),
                encoder.encode(password),
                admin,
                Permissions.valid(permissions)
        );
        return repo.save(user);
    }

    /** Retorna o usuário se credenciais válidas e ativo; senão null. */
    public AppUser authenticate(String username, String password) {
        AppUser user = repo.findByUsername(username == null ? "" : username.strip()).orElse(null);
        if (user == null || !user.isActive()) return null;
        if (!encoder.matches(password, user.getHashedPassword())) return null;
        return user;
    }
}
